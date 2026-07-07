package com.gu.mediaservice.lib.elasticsearch.client

import scala.concurrent.duration.FiniteDuration

// ─── Sort definitions ────────────────────────────────────────────────────────

sealed trait GridSort
case class GridFieldSort(field: String, order: GridSortOrder) extends GridSort

// ─── Runtime mapping ─────────────────────────────────────────────────────────

/** Replaces com.sksamuel.elastic4s.requests.searches.RuntimeMapping */
case class GridRuntimeMapping(
  field: String,
  `type`: String,
  scriptSource: String
)

// ─── Alias actions ───────────────────────────────────────────────────────────

sealed trait GridAliasAction
case class GridAddAlias(index: String, alias: String)    extends GridAliasAction
case class GridRemoveAlias(index: String, alias: String) extends GridAliasAction

// ─── Bulk operations ─────────────────────────────────────────────────────────

sealed trait GridBulkOperation
case class GridBulkUpdateByScript(index: String, id: String, script: GridEsScript) extends GridBulkOperation
case class GridBulkDelete(index: String, id: String)                               extends GridBulkOperation

// ─── Search request ──────────────────────────────────────────────────────────

/**
 * Immutable search request – mirrors the elastic4s builder pattern.
 * Build one via [[GridEsQueryDsl.search]] and chain methods.
 */
case class GridSearchRequest(
  index: Seq[String],
  query: GridEsQuery                    = MatchAllQuery,
  from: Option[Int]                     = None,
  size: Option[Int]                     = None,
  sorts: Seq[GridSort]                  = Nil,
  aggregations: Seq[GridEsAggregation]  = Nil,
  trackTotalHits: Option[Boolean]       = None,
  storedFields: Seq[String]             = Nil,
  scriptFields: Seq[GridScriptField]    = Nil,
  runtimeMappings: Seq[GridRuntimeMapping] = Nil,
  fetchSource: Boolean                  = true,
  version: Boolean                      = false,
  knn: Option[GridKnnQuery]             = None,
  scroll: Option[FiniteDuration]        = None,
  timeout: Option[FiniteDuration]       = None
) {

  // ── Query ─────────────────────────────────────────────────────────────────
  def query(q: GridEsQuery): GridSearchRequest       = copy(query = q)
  def bool(q: BoolQuery): GridSearchRequest          = copy(query = q)

  // ── Pagination ────────────────────────────────────────────────────────────
  def from(n: Int): GridSearchRequest                = copy(from = Some(n))
  def size(n: Int): GridSearchRequest                = copy(size = Some(n))
  /** Alias for size – matches elastic4s search(…) limit n pattern */
  def limit(n: Int): GridSearchRequest               = copy(size = Some(n))

  // ── Sorting ───────────────────────────────────────────────────────────────
  def sortBy(s: Seq[GridSort]): GridSearchRequest    = copy(sorts = s)
  // Note: sortBy(GridSort*) removed to avoid erasure clash with sortBy(Seq)
  def sortByFieldAsc(field: String): GridSearchRequest  = sortBy(Seq(GridFieldSort(field, GridSortOrder.ASC)))
  def sortByFieldDesc(field: String): GridSearchRequest = sortBy(Seq(GridFieldSort(field, GridSortOrder.DESC)))

  // ── Aggregations ──────────────────────────────────────────────────────────
  def aggregations(aggs: Iterable[GridEsAggregation]): GridSearchRequest = copy(aggregations = aggs.toSeq)
  def aggregations(aggs: GridEsAggregation*): GridSearchRequest          = copy(aggregations = aggs)
  /** Alias for aggregations */
  def aggs(aggs: GridEsAggregation*): GridSearchRequest                  = copy(aggregations = aggs)

  // ── Misc ──────────────────────────────────────────────────────────────────
  def trackTotalHits(b: Boolean): GridSearchRequest  = copy(trackTotalHits = Some(b))
  def storedFields(fields: String*): GridSearchRequest = copy(storedFields = fields)
  // Note: storedFields(Seq[String]) removed to avoid erasure clash
  def scriptfields(sf: Seq[GridScriptField]): GridSearchRequest = copy(scriptFields = sf)
  def runtimeMappings(ms: Seq[GridRuntimeMapping]): GridSearchRequest = copy(runtimeMappings = ms)
  def fetchSource(b: Boolean): GridSearchRequest     = copy(fetchSource = b)
  def version(b: Boolean): GridSearchRequest         = copy(version = b)
  def scroll(d: FiniteDuration): GridSearchRequest   = copy(scroll = Some(d))
  def timeout(t: FiniteDuration): GridSearchRequest  = copy(timeout = Some(t))
  def knn(k: GridKnnQuery): GridSearchRequest        = copy(knn = Some(k))
}

object GridSearchRequest {
  /** Create a request for a single index. */
  def apply(index: String): GridSearchRequest = new GridSearchRequest(index = Seq(index))
  def apply(index: String, q: GridEsQuery): GridSearchRequest =
    new GridSearchRequest(index = Seq(index), query = q)
}

