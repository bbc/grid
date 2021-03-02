package lib.elasticsearch

import com.gu.mediaservice.lib.auth.Authentication.Principal
import com.gu.mediaservice.lib.config.GridConfigResources
import com.gu.mediaservice.lib.elasticsearch.{ElasticSearchConfig, ElasticSearchExecutions}
import com.gu.mediaservice.lib.logging.{LogMarker, MarkerMap}
import com.gu.mediaservice.model._
import com.sksamuel.elastic4s.ElasticDsl
import com.sksamuel.elastic4s.ElasticDsl.{DeleteByQueryHandler, IndexHandler, RefreshIndexHandler, deleteByQuery, indexInto, matchAllQuery}
import com.whisk.docker.impl.spotify.DockerKitSpotify
import com.whisk.docker.scalatest.DockerTestKit
import com.whisk.docker.{DockerContainer, DockerKit, DockerReadyChecker}
import lib.{MediaApiConfig, MediaApiMetrics}
import org.joda.time.DateTime
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.time.{Milliseconds, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FunSpec, Matchers}
import play.api.Configuration
import play.api.libs.json.{JsString, Json}
import play.api.mvc.AnyContent
import play.api.mvc.Security.AuthenticatedRequest

import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Properties

trait ElasticSearchTestBase extends FunSpec with BeforeAndAfterAll with BeforeAndAfterEach
  with Matchers with ScalaFutures with Fixtures with DockerKit with DockerTestKit
  with DockerKitSpotify with ConditionFixtures with Eventually with ElasticSearchExecutions
  with MockitoSugar {

  implicit val request = mock[AuthenticatedRequest[AnyContent, Principal]]

  protected val index = "images"
  protected val NOT_USED_IN_TEST = "not used in test"
  protected val MOCK_CONFIG_KEYS = Seq(
    "auth.keystore.bucket",
    "persistence.identifier",
    "thrall.kinesis.stream.name",
    "thrall.kinesis.lowPriorityStream.name",
    "domain.root",
    "s3.config.bucket",
    "s3.usagemail.bucket",
    "quota.store.key",
    "es.index.aliases.read",
    "es6.url",
    "es6.cluster",
    "s3.image.bucket",
    "s3.thumb.bucket",
    "grid.stage",
    "grid.appName"
  )

  val interval = Interval(Span(100, Milliseconds))
  val timeout = Timeout(Span(10, Seconds))

  protected val expectedNumberOfImages = images.size
  protected val oneHundredMilliseconds = Duration(100, MILLISECONDS)
  protected val fiveSeconds = Duration(5, SECONDS)

  val useEsDocker = Properties.envOrElse("USE_DOCKER_FOR_TESTS", "true").toBoolean
  val es6TestUrl = Properties.envOrElse("ES6_TEST_URL", "http://localhost:9200")

  val mediaApiConfig = new MediaApiConfig(GridConfigResources(
    Configuration.from(Map(
      "es6.shards" -> 0,
      "es6.replicas" -> 0,
      "field.aliases" -> List(
        Map(
          "elasticsearchPath" -> "fileMetadata.xmp.org:ProgrammeMaker",
          "alias" -> "orgProgrammeMaker",
          "label" -> "Organization Programme Maker",
          "displaySearchHint" -> false
        ),
        Map(
          "elasticsearchPath" -> "fileMetadata.xmp.aux:Lens",
          "alias" -> "auxLens",
          "label" -> "Aux Lens",
          "displaySearchHint" -> false
        ),
        Map(
          "elasticsearchPath" -> "fileMetadata.iptc.Caption Writer/Editor",
          "alias" -> "captionWriter",
          "label" -> "Caption Writer / Editor",
          "displaySearchHint" -> true
        )
      )
    ) ++ MOCK_CONFIG_KEYS.map(_ -> NOT_USED_IN_TEST).toMap),
    null
  ))

  val mediaApiMetrics = new MediaApiMetrics(mediaApiConfig)
  val elasticConfig = ElasticSearchConfig(alias = "readalias", url = es6TestUrl,
    cluster = "media-service-test", shards = 1, replicas = 0)

  val ES = new ElasticSearch(mediaApiConfig, mediaApiMetrics, elasticConfig, () => List.empty)
  val client = ES.client

  val esContainer = if (useEsDocker) Some(DockerContainer("docker.elastic.co/elasticsearch/elasticsearch:7.5.2")
    .withPorts(9200 -> Some(9200))
    .withEnv("cluster.name=media-service", "xpack.security.enabled=false", "discovery.type=single-node", "network.host=0.0.0.0")
    .withReadyChecker(
      DockerReadyChecker.HttpResponseCode(9200, "/", Some("0.0.0.0")).within(10.minutes).looped(40, 1250.millis)
    )
  ) else None

  final override def dockerContainers: List[DockerContainer] =
    esContainer.toList ++ super.dockerContainers

  final override val StartContainersTimeout = 1.minute

  lazy val images = Seq(
    createImage("getty-image-1", Agency("Getty Images")),
    createImage("getty-image-2", Agency("Getty Images")),
    createImage("ap-image-1", Agency("AP")),
    createImage("gnm-image-1", Agency("GNM")),
    createImage(UUID.randomUUID().toString, Handout()),
    createImage("iron-suit", CommissionedPhotographer("Iron Man")),
    createImage("green-giant", StaffIllustrator("Hulk")),
    createImage("hammer-hammer-hammer", ContractIllustrator("Thor")),
    createImage("green-leaf", StaffPhotographer("Yellow Giraffe", "The Guardian")),
    createImage(UUID.randomUUID().toString, Handout(), usages = List(createDigitalUsage())),

    createImageUploadedInThePast("persisted-because-edited").copy(
      userMetadata = Some(Edits(metadata = ImageMetadata(credit = Some("author"))))
    ),

    createImageUploadedInThePast("test-image-14-unedited"),

    createImageUploadedInThePast("persisted-because-usage").copy(
      usages = List(createPrintUsage())
    ),

    // available for syndication
    createImageForSyndication(
      id = "test-image-1",
      rightsAcquired = true,
      Some(DateTime.parse("2018-01-01T00:00:00")),
      Some(createSyndicationLease(allowed = true, "test-image-1"))
    ),

    // has a digital usage, still eligible for syndication
    createImageForSyndication(
      id = "test-image-2",
      rightsAcquired = true,
      Some(DateTime.parse("2018-01-01T00:00:00")),
      Some(createSyndicationLease(allowed = true, "test-image-2")),
      List(createDigitalUsage())
    ),

    // has syndication usage, not available for syndication
    createImageForSyndication(
      id = "test-image-3",
      rightsAcquired = true,
      Some(DateTime.parse("2018-01-01T00:00:00")),
      Some(createSyndicationLease(allowed = true, "test-image-3")),
      List(createDigitalUsage(), createSyndicationUsage())
    ),

    // rights acquired, explicit allow syndication lease and unknown publish date, available for syndication
    createImageForSyndication(
      id = "test-image-4",
      rightsAcquired = true,
      None,
      Some(createSyndicationLease(allowed = true, "test-image-4"))
    ),

    // explicit deny syndication lease with no end date, not available for syndication
    createImageForSyndication(
      id = "test-image-5",
      rightsAcquired = true,
      None,
      Some(createSyndicationLease(allowed = false, "test-image-5"))
    ),

    // explicit deny syndication lease with end date before now, available for syndication
    createImageForSyndication(
      id = "test-image-6",
      rightsAcquired = true,
      Some(DateTime.parse("2018-01-01T00:00:00")),
      Some(createSyndicationLease(allowed = false, "test-image-6", endDate = Some(DateTime.parse("2018-01-01T00:00:00"))))
    ),

    // images published after "today", not available for syndication
    createImageForSyndication(
      id = "test-image-7",
      rightsAcquired = true,
      Some(DateTime.parse("2018-07-02T00:00:00")),
      Some(createSyndicationLease(allowed = false, "test-image-7"))
    ),

    // with fileMetadata
    createImageForSyndication(
      id = "test-image-8",
      rightsAcquired = true,
      Some(DateTime.parse("2018-07-03T00:00:00")),
      None,
      fileMetadata = Some(FileMetadata(
        iptc = Map(
          "Caption/Abstract" -> "the description",
          "Caption Writer/Editor" -> "the editor"
        ),
        exif = Map(
          "Copyright" -> "the copyright",
          "Artist" -> "the artist"
        ),
        xmp = Map(
        "foo" -> JsString("bar"),
        "toolong" -> JsString(stringLongerThan(100000)),
        "org:ProgrammeMaker" -> JsString("xmp programme maker"),
        "aux:Lens" -> JsString("xmp aux lens")
      )))
    ),

    // no rights acquired, not available for syndication
    createImageForSyndication("test-image-13", rightsAcquired = false, None, None),

    // Agency image with published usage yesterday
    createImageForSyndication(
      id = "test-image-9",
      rightsAcquired = false,
      None,
      None,
      usageRights = agency,
      usages = List(createDigitalUsage(date = DateTime.now.minusDays(1)))
    ),

    // Agency image with published just now
    createImageForSyndication(
      id = "test-image-10",
      rightsAcquired = false,
      None,
      Some(createSyndicationLease(allowed = true, "test-image-10")),
      usageRights = agency,
      usages = List(createDigitalUsage(date = DateTime.now))
    ),

    // Screen grab with rights acquired, not eligible for syndication review
    createImageForSyndication(
      id = "test-image-11",
      rightsAcquired = true,
      rcsPublishDate = None,
      lease = None,
      usageRights = screengrab,
      usages = List(createDigitalUsage(date = DateTime.now))
    ),

    // Staff photographer with rights acquired, eligible for syndication review
    createImageForSyndication(
      id = "test-image-12",
      rightsAcquired = true,
      rcsPublishDate = None,
      lease = None,
      usageRights = staffPhotographer,
      usages = List(createDigitalUsage(date = DateTime.now))
    ),

    // TODO this test image *should* be in `AwaitingReviewForSyndication` but instead its in `BlockedForSyndication`
    // see https://www.elastic.co/guide/en/elasticsearch/reference/current/nested.html to understand why
    //    createImageForSyndication(
    //      id = "active-deny-syndication-with-expired-crop",
    //      rightsAcquired = true,
    //      Some(DateTime.parse("2018-01-01T00:00:00")),
    //      None
    //    ).copy(
    //      leases =  LeasesByMedia(
    //        lastModified = None,
    //        leases = List(
    //          createLease(
    //            DenySyndicationLease,
    //            imageId = "syndication-review-foo"
    //          ),
    //          createLease(
    //            AllowUseLease,
    //            imageId = "syndication-review-foo",
    //            endDate = Some(DateTime.now().minusDays(100))
    //          )
    //        )
    //      )
    //    )
  )

  override def beforeAll {
    super.beforeAll()

    ES.ensureAliasAssigned()
    purgeTestImages

    Await.ready(saveImages(images), 1.minute)
    // allow the cluster to distribute documents... eventual consistency!
    eventually(timeout(fiveSeconds), interval(oneHundredMilliseconds))(totalImages shouldBe expectedNumberOfImages)
  }

  override def afterAll: Unit = {
    super.afterAll()
  }

  protected def saveImages(images: Seq[Image]) = {
    implicit val logMarker: LogMarker = MarkerMap()

    Future.sequence(images.map { i =>
      executeAndLog(indexInto(index) id i.id source Json.stringify(Json.toJson(i)), s"Indexing test image")
    })
  }

  protected def totalImages: Long = Await.result(ES.totalImages(), oneHundredMilliseconds)

  protected def purgeTestImages = {
    implicit val logMarker: LogMarker = MarkerMap()

    def deleteImages = executeAndLog(deleteByQuery(index, matchAllQuery()), s"Deleting images")

    Await.result(deleteImages, fiveSeconds)
    eventually(timeout(fiveSeconds), interval(oneHundredMilliseconds))(totalImages shouldBe 0)
  }
}
