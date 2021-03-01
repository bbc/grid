package lib

import com.gu.mediaservice.lib.config.GridConfigResources
import com.gu.mediaservice.lib.elasticsearch.ElasticSearchConfig
import com.gu.mediaservice.model._
import com.gu.mediaservice.model.usage.{PendingUsageStatus, PrintUsage, Usage}
import lib.elasticsearch.{ElasticSearch, ElasticSearchTestBase}
import org.joda.time.DateTime.now
import org.scalatest.Inside.inside
import org.scalatest.{FunSpec, Matchers}
import play.api.Configuration
import play.api.libs.json._

import scala.concurrent.Await
import scala.concurrent.duration._

class ImageResponseTest extends FunSpec with Matchers with ElasticSearchTestBase {

  private val mediaApiConfig = new MediaApiConfig(GridConfigResources(
    Configuration.from(Map(
      "es6.shards" -> 0,
      "es6.replicas" -> 0,
      "fieldAliases" -> List(
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

  private val mediaApiMetrics = new MediaApiMetrics(mediaApiConfig)
  val elasticConfig = ElasticSearchConfig(alias = "readalias", url = es6TestUrl,
    cluster = "media-service-test", shards = 1, replicas = 0)

  private val ES = new ElasticSearch(mediaApiConfig, mediaApiMetrics, elasticConfig, () => List.empty)
  val client = ES.client

  override def beforeAll {
    super.beforeAll()

    ES.ensureAliasAssigned()
    purgeTestImages(ES)

    Await.ready(saveImages(images), 1.minute)
    // allow the cluster to distribute documents... eventual consistency!
    eventually(timeout(fiveSeconds), interval(oneHundredMilliseconds))(totalImages(ES) shouldBe expectedNumberOfImages)
  }

  override def afterAll = purgeTestImages(ES)

  it("should replace \\r linebreaks with \\n") {
    val text = "Here is some text\rthat spans across\rmultiple lines\r"
    val normalisedText = ImageResponse.normaliseNewlineChars(text)
    normalisedText shouldBe "Here is some text\nthat spans across\nmultiple lines\n"
  }

  it("should replace \\r\\n linebreaks with \\n") {
    val text = "Here is some text\r\nthat spans across\r\nmultiple lines\r\n"
    val normalisedText = ImageResponse.normaliseNewlineChars(text)
    normalisedText shouldBe "Here is some text\nthat spans across\nmultiple lines\n"
  }

  it("not cause a stack overflow when many consecutive newline characters are present") {
    val text = "\n\r\n\n\n\r\r\r\n" * 10000
    val normalisedText = ImageResponse.normaliseNewlineChars(text)
    normalisedText shouldBe "\n"
  }

  it("should not touch \\n linebreaks") {
    val text = "Here is some text\nthat spans across\nmultiple lines\n"
    val normalisedText = ImageResponse.normaliseNewlineChars(text)
    normalisedText shouldBe "Here is some text\nthat spans across\nmultiple lines\n"
  }

  it("should indicate if image can be deleted" +
    "(it can be deleted if there is no exports or usages)") {

    import TestUtils._

    val testCrop = Crop(Some("crop-id"), None, None, CropSpec("test-uri", Bounds(0, 0, 0, 0), None), None, Nil)
    val testUsage = Usage(id = "usage-id", references = Nil, platform = PrintUsage, media = "test", status = PendingUsageStatus, dateAdded = None, dateRemoved = None, now())

    val imgWithNoExportsAndUsages = img
    import ImageResponse.canImgBeDeleted
    canImgBeDeleted(imgWithNoExportsAndUsages) shouldEqual true
    val imgWithExportsAndUsages = img.copy(exports = List(testCrop)).copy(usages = List(testUsage))
    canImgBeDeleted(imgWithExportsAndUsages) shouldEqual false
    val imgWithOnlyUsages = img.copy(usages = List(testUsage))
    canImgBeDeleted(imgWithOnlyUsages) shouldEqual false
    val imgWithOnlyExports = img.copy(exports = List(testCrop))
    canImgBeDeleted(imgWithOnlyExports) shouldEqual false
  }

  it("should add aliases field to image json") {
    val imageId = "test-image-8"

    whenReady(ES.getImageWithSourceById(imageId)) { r =>
      r.get.instance.id shouldEqual(imageId)

      val json = Json.toJson(r.get.instance)
      val transformed = json.transform(ImageResponse.addAliases(mediaApiConfig, r.get)).get

      inside(transformed) {
        case jso: JsObject =>
          jso.fields.map(_._1) shouldBe Seq(
            "id",
            "uploadTime",
            "uploadedBy",
            "identifiers",
            "uploadInfo",
            "source",
            "thumbnail",
            "fileMetadata",
            "metadata",
            "originalMetadata",
            "usageRights",
            "originalUsageRights",
            "exports",
            "usages",
            "leases",
            "collections",
            "syndicationRights",
            "aliases"
          )

          jso.transform(
            (__ \ 'aliases).json.pick
          ).get shouldEqual Json.obj(
            "orgProgrammeMaker" -> JsString("xmp programme maker"),
            "auxLens" -> JsString("xmp aux lens"),
            "captionWriter" -> JsString("the editor")
          )
      }
    }
  }

  it("should add empty aliases field to image json") {
    val imageId: String = "getty-image-1"

    whenReady(ES.getImageWithSourceById(imageId)) { r =>
      r.get.instance.id shouldEqual(imageId)

      val json = Json.toJson(r.get.instance)
      val transformed = json.transform(ImageResponse.addAliases(mediaApiConfig, r.get)).get

      inside(transformed) {
        case jso: JsObject =>
          jso.fields.map(_._1) shouldBe Seq(
            "id",
            "uploadTime",
            "uploadedBy",
            "identifiers",
            "uploadInfo",
            "source",
            "thumbnail",
            "fileMetadata",
            "metadata",
            "originalMetadata",
            "usageRights",
            "originalUsageRights",
            "exports",
            "usages",
            "leases",
            "collections",
            "aliases"
          )

          jso.transform(
            (__ \ 'aliases).json.pick
          ).get shouldEqual Json.obj()
      }
    }
  }
}
