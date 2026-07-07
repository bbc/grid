package com.gu.mediaservice.lib.elasticsearch.client

import com.gu.mediaservice.lib.logging.{GridLogging, LogMarker, MarkerMap, MarkerUtils, Stopwatch}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Pure interface for all OpenSearch / Elasticsearch interactions.
 *
 * No com.sksamuel.elastic4s types appear anywhere in this trait or its
 * parameter / return types.  The only concrete implementation shipped here is
 * [[com.gu.mediaservice.lib.elasticsearch.client.Elastic4sGridEsClient]].
 */
trait GridEsClient {

  // ─── Document operations ────────────────────────────────────────────────

  def getDocument(index: String, id: String, sourceIncludes: Seq[String] = Nil)
                 (implicit ec: ExecutionContext): Future[GridGetResponse]

  def indexDocument(index: String, id: String, source: String)
                   (implicit ec: ExecutionContext): Future[GridIndexResponse]

  def updateDocumentWithScript(index: String, id: String, script: GridEsScript)
                               (implicit ec: ExecutionContext): Future[GridUpdateResponse]

  def upsertDocumentWithScript(index: String, id: String, script: GridEsScript, upsertDoc: String)
                               (implicit ec: ExecutionContext): Future[GridUpdateResponse]

  def updateDocumentWithDoc(index: String, id: String, doc: String)
                            (implicit ec: ExecutionContext): Future[GridUpdateResponse]

  def deleteDocument(index: String, id: String)
                    (implicit ec: ExecutionContext): Future[GridDeleteResponse]

  // ─── Search operations ──────────────────────────────────────────────────

  def search(request: GridSearchRequest)
            (implicit ec: ExecutionContext): Future[GridSearchResponse]

  def count(index: String, query: GridEsQuery)
           (implicit ec: ExecutionContext): Future[Long]

  def catCount(index: String)
              (implicit ec: ExecutionContext): Future[Long]

  def startScroll(request: GridSearchRequest)
                 (implicit ec: ExecutionContext): Future[GridSearchResponse]

  def continueScroll(scrollId: String, keepAlive: FiniteDuration)
                    (implicit ec: ExecutionContext): Future[GridSearchResponse]

  def clearScroll(scrollId: String)
                 (implicit ec: ExecutionContext): Future[Unit]

  // ─── Bulk operations ────────────────────────────────────────────────────

  def bulk(operations: Seq[GridBulkOperation])
          (implicit ec: ExecutionContext): Future[GridBulkResponse]

  // ─── Index management ───────────────────────────────────────────────────

  def indexExists(index: String)
                 (implicit ec: ExecutionContext): Future[Boolean]

  /**
   * Create an index using a pre-serialised JSON body.
   * The body should be the full create-index request body, e.g.:
   * {{{{"settings":{…},"mappings":{…}}}}}
   *
   * @param additionalSettings Extra index settings (e.g. max_result_window).
   */
  def createIndex(index: String,
                  bodyJson: String,
                  shards: Int,
                  replicas: Int,
                  additionalSettings: Map[String, Any] = Map.empty)
                 (implicit ec: ExecutionContext): Future[Either[GridEsError, Boolean]]

  def indexStats(index: String)
               (implicit ec: ExecutionContext): Future[GridIndexStatsResponse]

  def getAliases(alias: String)
               (implicit ec: ExecutionContext): Future[Seq[GridIndexWithAliases]]

  def modifyAliases(actions: Seq[GridAliasAction])
                   (implicit ec: ExecutionContext): Future[Either[GridEsError, Boolean]]

  // ─── Cluster operations ─────────────────────────────────────────────────

  def waitForGreenHealth(timeout: String)
                        (implicit ec: ExecutionContext): Future[Boolean]
}

// ─── Logging mixin ───────────────────────────────────────────────────────────

/**
 * Mixin trait that wraps any `Future[T]` produced by [[GridEsClient]] calls
 * with structured logging and exception classification.
 *
 * Replace [[com.gu.mediaservice.lib.elasticsearch.ElasticSearchExecutions]] with
 * this trait once consumers have been migrated to call [[GridEsClient]] directly.
 *
 * Usage in subclasses:
 * {{{
 *   executeAndLog(gridEsClient.getDocument(index, id), "fetching image")
 * }}}
 */
trait GridEsExecutions extends GridLogging with MarkerUtils {

  protected def gridEsClient: GridEsClient

  /**
   * Wrap an already-running [[Future]] with timing and structured log output.
   *
   * @param op                 The future to observe (already in-flight or lazy via `=>`)
   * @param message            Human-readable description for log lines
   * @param notFoundSuccessful When true a [[GridEsNotFoundException]] is logged as a
   *                           warning rather than an error and the future still succeeds
   *                           (useful for update operations on potentially absent docs)
   */
  def executeAndLog[T](
    op: Future[T],
    message: String,
    notFoundSuccessful: Boolean = false
  )(implicit ec: ExecutionContext, logMarker: LogMarker): Future[T] = {
    val stopwatch = Stopwatch.start

    val result = op.transform {
      case s @ Success(_) => s
      case Failure(GridEsNotFoundException) if notFoundSuccessful =>
        logger.underlying.warn(logMarker.toLogMarker, s"$message – document not found (notFoundSuccessful=true)")
        Success(null.asInstanceOf[T]) // caller must guard – mirrors elastic4s 404 behaviour
      case f @ Failure(_) => f
    }

    result.foreach { _ =>
      val elapsed = stopwatch.elapsed
      val marker = combineMarkers(logMarker, elapsed).toLogMarker
      logger.underlying.info(marker, s"$message – completed in ${elapsed.toMillis} ms")
    }

    result.failed.foreach {
      case GridEsNotFoundException =>
        val elapsed = stopwatch.elapsed
        val marker = combineMarkers(logMarker, elapsed, MarkerMap(Map("reason" -> "GridEsNotFoundException"))).toLogMarker
        logger.underlying.error(marker, s"$message – document not found")
      case e =>
        val elapsed = stopwatch.elapsed
        val marker = combineMarkers(logMarker, elapsed, MarkerMap(Map("reason" -> Option(e.getMessage).getOrElse("null")))).toLogMarker
        logger.underlying.error(marker, s"$message – failed", e)
    }

    result
  }

  /** Convenience overload accepting a call-by-name block. */
  def executeAndLog[T](
    message: String,
    notFoundSuccessful: Boolean
  )(op: => Future[T])(implicit ec: ExecutionContext, logMarker: LogMarker): Future[T] =
    executeAndLog(op, message, notFoundSuccessful)
}

