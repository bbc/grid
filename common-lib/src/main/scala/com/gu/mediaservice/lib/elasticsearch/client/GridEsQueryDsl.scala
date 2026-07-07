package com.gu.mediaservice.lib.elasticsearch.client

import scalaz.NonEmptyList

/**
 * Drop-in replacement for `import com.sksamuel.elastic4s.ElasticDsl._` and
 * `import com.sksamuel.elastic4s.ElasticApi.*`.
 *
 * All factory methods return internal types only.  No elastic4s types leak out.
 *
 * Usage: `import com.gu.mediaservice.lib.elasticsearch.client.GridEsQueryDsl._`
 */
object GridEsQueryDsl {

  // ─── Match queries ─────────────────────────────────────────────────────────

  def matchAllQuery(): MatchAllQuery.type   = MatchAllQuery
  def matchNoneQuery(): MatchNoneQuery.type = MatchNoneQuery

  def matchQuery(field: String, value: Any): MatchQuery = MatchQuery(field, value)

  def matchPhraseQuery(field: String, value: Any): MatchPhraseQuery = MatchPhraseQuery(field, value)

  def multiMatchQuery(value: String): MultiMatchQuery = MultiMatchQuery(value)

  // ─── Term-level queries ────────────────────────────────────────────────────

  def termQuery(field: String, value: Any): TermQuery       = TermQuery(field, value)
  def termsQuery(field: String, values: Iterable[Any]): TermsQuery = TermsQuery(field, values.toSeq)
  def termsQuery(field: String, values: NonEmptyList[Any]): TermsQuery = TermsQuery(field, values.list.toList)

  def rangeQuery(field: String): RangeQuery = RangeQuery(field)

  def existsQuery(field: String): ExistsQuery = ExistsQuery(field)

  def idsQuery(ids: Iterable[String]): IdsQuery = IdsQuery(ids.toSeq)
  def idsQuery(id: String, ids: String*): IdsQuery = IdsQuery(id +: ids)

  def prefixQuery(field: String, prefix: String): PrefixQuery = PrefixQuery(field, prefix)
  def regexQuery(field: String, regex: String): RegexQuery    = RegexQuery(field, regex)

  def nestedQuery(path: String, query: GridEsQuery): NestedQuery = NestedQuery(path, query)

  def pinnedQuery(ids: Seq[String], organic: GridEsQuery): PinnedQuery = PinnedQuery(ids, organic)
  def pinnedQuery(ids: Iterable[String], organic: GridEsQuery): PinnedQuery = PinnedQuery(ids.toSeq, organic)

  // ─── Bool / compound queries ───────────────────────────────────────────────

  def boolQuery(): BoolQuery = BoolQuery()

  /** Creates a BoolQuery with all supplied queries in the *must* clause. */
  def must(queries: GridEsQuery*): BoolQuery = BoolQuery().must(queries: _*)
  def must(queries: Iterable[GridEsQuery]): BoolQuery = BoolQuery().must(queries)

  /** Creates a BoolQuery with all supplied queries in the *should* clause. */
  def should(queries: GridEsQuery*): BoolQuery = BoolQuery().should(queries: _*)
  def should(queries: Iterable[GridEsQuery]): BoolQuery = BoolQuery().should(queries)

  /** Creates a BoolQuery with all supplied queries in the *must_not* clause. */
  def not(queries: GridEsQuery*): BoolQuery = BoolQuery().not(queries: _*)
  def not(queries: Iterable[GridEsQuery]): BoolQuery = BoolQuery().not(queries)

  // ─── KNN ──────────────────────────────────────────────────────────────────

  def knnQuery(field: String): GridKnnQuery = GridKnnQuery(field)

  // ─── Aggregations ─────────────────────────────────────────────────────────

  def termsAgg(name: String, field: String): GridTermsAggregation   = GridTermsAggregation(name, field)
  def topHitsAgg(name: String): GridTopHitsAggregation              = GridTopHitsAggregation(name)
  def dateHistogramAgg(name: String, field: String): GridDateHistogramAggregation =
    GridDateHistogramAggregation(name, field)
  def filterAgg(name: String, query: GridEsQuery): GridFilterAggregation =
    GridFilterAggregation(name, query)

  // ─── Sort helpers ─────────────────────────────────────────────────────────

  def fieldSort(field: String): GridFieldSort = GridFieldSort(field, GridSortOrder.ASC)

  // ─── Search request entry-point ───────────────────────────────────────────

  /** Start building a search request: search("myIndex").query(q).size(10) */
  def search(index: String, extra: String*): GridSearchRequest =
    GridSearchRequest(index = index +: extra)
  def search(indexes: Iterable[String]): GridSearchRequest =
    GridSearchRequest(index = indexes.toSeq)
}

