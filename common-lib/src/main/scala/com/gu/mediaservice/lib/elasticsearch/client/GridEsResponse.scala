package com.gu.mediaservice.lib.elasticsearch.client

// ─── Document responses ──────────────────────────────────────────────────────

case class GridGetResponse(
  id: String,
  index: String,
  found: Boolean,
  source: Option[String]       = None,
  version: Long                = 0L,
  storedFields: Map[String, Any] = Map.empty
) {
  def sourceAsString: String = source.getOrElse("")
  /** Access a field from the stored-fields section (not the _source). */
  def sourceFieldOpt(field: String): Option[Any] = storedFields.get(field)
}

case class GridIndexResponse(indexName: String)

case class GridUpdateResponse()

case class GridDeleteResponse()

// ─── Search responses ────────────────────────────────────────────────────────

case class GridSearchHit(
  id: String,
  index: String,
  source: Option[String]       = None,
  version: Option[Long]        = None,
  fields: Map[String, Any]     = Map.empty,
  score: Float                 = 0f
) {
  def sourceAsString: String = source.getOrElse("")
}

case class GridTotalHits(value: Long)

case class GridSearchHits(total: GridTotalHits, hits: Seq[GridSearchHit]) {
  def size: Int           = hits.size
  def isEmpty: Boolean    = hits.isEmpty
  def nonEmpty: Boolean   = hits.nonEmpty
  /** Compatibility alias used in media-api: r.result.hits.total.value */
  def maxScore: Float     = hits.headOption.map(_.score).getOrElse(0f)
}

case class GridSearchResponse(
  hits: GridSearchHits,
  scrollId: Option[String]         = None,
  aggregations: GridAggregations   = GridAggregations.empty,
  took: Long                       = 0L,
  timedOut: Boolean                = false
) {
  /** Convenience alias: r.result.totalHits */
  def totalHits: Long = hits.total.value
  def isTimedOut: Boolean = timedOut
}

// ─── Bulk responses ──────────────────────────────────────────────────────────

case class GridBulkItemError(reason: String)

case class GridBulkItem(id: String, error: Option[GridBulkItemError] = None)

case class GridBulkResponse(items: Seq[GridBulkItem])

// ─── Index management responses ──────────────────────────────────────────────

case class GridIndexDocCount(count: Long)
case class GridIndexTotals(docs: GridIndexDocCount)
case class GridIndexStats(total: GridIndexTotals)

case class GridIndexStatsResponse(indices: Map[String, GridIndexStats])

case class GridIndexWithAliases(name: String, aliases: Seq[String])

// ─── Aggregation results ─────────────────────────────────────────────────────

/** Base type for all aggregation results. */
sealed trait GridAggregationResult

case class GridTermsBucket(
  key: String,
  docCount: Long,
  private val subAggs: GridAggregations
) {
  def terms(name: String): GridTermsAggResult          = subAggs.terms(name)
  def topHits(name: String): GridTopHitsAggResult      = subAggs.topHits(name)
  def dateHistogram(name: String): GridDateHistogramAggResult = subAggs.dateHistogram(name)
}

case class GridTermsAggResult(
  buckets: Seq[GridTermsBucket],
  otherDocCount: Long = 0L
) extends GridAggregationResult

case class GridAggregationHit(id: String)

case class GridTopHitsAggResult(hits: Seq[GridAggregationHit]) extends GridAggregationResult

case class GridDateHistogramBucket(key: String, docCount: Long)

case class GridDateHistogramAggResult(buckets: Seq[GridDateHistogramBucket]) extends GridAggregationResult

/** Result of a filter aggregation – holds a docCount and optional sub-aggregations. */
abstract class GridFilterAggResult extends GridAggregationResult {
  def docCount: Long
  def terms(name: String): GridTermsAggResult
  def topHits(name: String): GridTopHitsAggResult
  def dateHistogram(name: String): GridDateHistogramAggResult
}

// ─── Aggregations access facade ──────────────────────────────────────────────

/**
 * Abstracts over the aggregation results container.
 * The elastic4s implementation is in Elastic4sGridEsClient; mock implementations can
 * be provided in tests.
 */
abstract class GridAggregations {
  def terms(name: String): GridTermsAggResult
  def topHits(name: String): GridTopHitsAggResult
  def dateHistogram(name: String): GridDateHistogramAggResult
  def filter(name: String): GridFilterAggResult
}

object GridAggregations {
  val empty: GridAggregations = new GridAggregations {
    private def nope(name: String): Nothing = throw new NoSuchElementException(s"No aggregation '$name' (empty GridAggregations)")
    def terms(name: String): GridTermsAggResult               = nope(name)
    def topHits(name: String): GridTopHitsAggResult           = nope(name)
    def dateHistogram(name: String): GridDateHistogramAggResult = nope(name)
    def filter(name: String): GridFilterAggResult             = nope(name)
  }
}

