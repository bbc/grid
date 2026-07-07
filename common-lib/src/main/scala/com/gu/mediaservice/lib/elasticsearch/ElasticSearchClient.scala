package com.gu.mediaservice.lib.elasticsearch

import com.gu.mediaservice.lib.elasticsearch.client._
import com.gu.mediaservice.lib.elasticsearch.client.GridEsQueryDsl._
import com.gu.mediaservice.lib.logging.{GridLogging, LogMarker, MarkerMap}
// Legacy elastic4s client kept only for createImageIndex (mapping serialisation).
// TODO: migrate createImageIndex to GridEsClient once Mappings/IndexSettings are ported.
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s._
import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s.requests.indexes.CreateIndexResponse

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

case class ElasticSearchImageCounts(
  catCount: Long,
  searchResponseCount: Long,
  indexStatsCount: Long,
  uploadedInLastFiveMinutes: Long
)

trait ElasticSearchClient extends ElasticSearchExecutions with GridLogging {

  private val tenSeconds    = Duration(10, SECONDS)
  private val thirtySeconds = Duration(30, SECONDS)

  def url: String

  def imagesCurrentAlias: String
  def imagesMigrationAlias: String
  lazy val imagesHistoricalAlias: String = "Images_Historical"

  protected val imagesIndexPrefix = "images"
  protected val imageType         = "image"
  val initialImagesIndex          = "images"

  def shards: Int
  def replicas: Int
  def includeDenseVectorMappings: Boolean

  // ─── Facade client (preferred) ─────────────────────────────────────────

  /** Facade over all OpenSearch operations.  Use this in new/migrated code. */
  lazy val gridEsClient: GridEsClient = new Elastic4sGridEsClient(url)

  // ─── Legacy elastic4s client (kept for createImageIndex mapping only) ──

  lazy val client: ElasticClient = {
    logger.info("Connecting to Elastic 8: " + url)
    ElasticClient(JavaClient(ElasticProperties(url)))
  }

  // ─── Startup ───────────────────────────────────────────────────────────

  def ensureIndexExistsAndAliasAssigned(): Unit = {
    logger.info(s"Checking alias $imagesCurrentAlias is assigned to index…")
    val indexForCurrentAlias = Await.result(getIndexForAlias(imagesCurrentAlias), tenSeconds)
    if (indexForCurrentAlias.isEmpty) {
      createIndexIfMissing(initialImagesIndex)
      assignAliasTo(initialImagesIndex, imagesCurrentAlias)
      waitUntilHealthy()
    }
  }

  def waitUntilHealthy(): Unit = {
    logger.info("waiting for cluster health to be green")
    val healthy = Await.result(gridEsClient.waitForGreenHealth("25s"), thirtySeconds)
    if (!healthy) throw new RuntimeException("cluster health could not be confirmed as green")
  }

  def healthCheck(): Future[Boolean] = {
    implicit val logMarker: MarkerMap = MarkerMap()
    gridEsClient.search(
      GridSearchRequest(imagesCurrentAlias).limit(0)
    ).map(_ => true).recover { case _ => false }
  }

  // ─── Alias helpers ─────────────────────────────────────────────────────

  case class IndexWithAliases(name: String, aliases: Seq[String])

  def getIndexForAlias(alias: String)(implicit logMarker: LogMarker = MarkerMap()): Future[Option[IndexWithAliases]] =
    gridEsClient.getAliases(alias).map(_.headOption.map { ia =>
      IndexWithAliases(name = ia.name, aliases = ia.aliases)
    })

  // ─── Stats / counts ────────────────────────────────────────────────────

  def countImages(indexName: String = imagesCurrentAlias): Future[ElasticSearchImageCounts] = {
    implicit val logMarker: MarkerMap = MarkerMap()

    for {
      catCnt    <- gridEsClient.catCount(indexName)
      searchCnt <- gridEsClient.search(GridSearchRequest(indexName).trackTotalHits(true).limit(0))
      stats     <- gridEsClient.indexStats(indexName)
      recent    <- gridEsClient.count(indexName, GridEsQueryDsl.rangeQuery("uploadTime").gte("now-5m"))
      maybeReal <- getIndexForAlias(indexName)
    } yield {
      val realIndexName = maybeReal.map(_.name).getOrElse(indexName)
      ElasticSearchImageCounts(
        catCount                 = catCnt,
        searchResponseCount      = searchCnt.totalHits,
        indexStatsCount          = stats.indices(realIndexName).total.docs.count,
        uploadedInLastFiveMinutes = recent
      )
    }
  }

  // ─── Index creation ────────────────────────────────────────────────────

  def createIndexIfMissing(index: String): Unit = {
    logger.info("Checking index exists…")
    val exists = Await.result(gridEsClient.indexExists(index), tenSeconds)
    if (!exists) createImageIndex(index)
  }

  /**
   * Creates the image index.  Still uses the legacy elastic4s client for
   * mapping serialisation until Mappings/IndexSettings are ported to the facade.
   *
   * TODO: port Mappings and IndexSettings to GridEsClient.createIndex(bodyJson).
   */
  def createImageIndex(index: String): Either[GridEsError, Boolean] = {
    logger.info(s"Creating image index '$index' with $shards shards and $replicas replicas")

    val maximumFieldsOverride   = Map("mapping.total_fields.limit" -> Integer.MAX_VALUE)
    val maximumPaginationOverride = Map("max_result_window" -> 101000)
    val overrides = maximumFieldsOverride ++ maximumPaginationOverride

    logger.warn("Applying non-recommended index setting overrides: " + overrides)

    val eventualResponse: Future[Response[CreateIndexResponse]] = client.execute {
      createIndex(index)
        .mapping(Mappings.imageMapping(includeDenseVectorMappings))
        .analysis(IndexSettings.analysis)
        .settings(overrides.asInstanceOf[Map[String, Any]])
        .shards(shards)
        .replicas(replicas)
    }

    val response = Await.result(eventualResponse, tenSeconds)
    logger.info("Got index create result: " + response)
    if (response.isError) {
      logger.error(response.error.reason)
      Left(GridEsError(`type` = response.error.`type`, reason = response.error.reason))
    } else {
      Right(response.result.acknowledged)
    }
  }

  // ─── Alias management ──────────────────────────────────────────────────

  def assignAliasTo(index: String, alias: String): Either[GridEsError, Boolean] = {
    logger.info(s"Assigning alias $alias to $index")
    Await.result(
      gridEsClient.modifyAliases(Seq(GridAddAlias(index, alias))),
      tenSeconds
    ).left.map { err =>
      logger.error(s"Failed to assign alias: ${err.reason}")
      err
    }
  }

  def changeAliasTo(newIndex: String, oldIndex: String, alias: String = imagesCurrentAlias): Unit = {
    logger.info(s"Reassigning alias $alias from $oldIndex to $newIndex")
    Await.result(
      gridEsClient.modifyAliases(Seq(
        GridRemoveAlias(oldIndex, alias),
        GridAddAlias(newIndex, alias)
      )),
      tenSeconds
    )
  }

  def removeAliasFrom(index: String, alias: String): Unit = {
    logger.info(s"Removing alias $alias from $index")
    Await.result(gridEsClient.modifyAliases(Seq(GridRemoveAlias(index, alias))), tenSeconds)
  }
}
