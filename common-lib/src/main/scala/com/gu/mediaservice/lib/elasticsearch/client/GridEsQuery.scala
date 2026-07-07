package com.gu.mediaservice.lib.elasticsearch.client

/** Internal query ADT – replaces all com.sksamuel.elastic4s query types.
 *  No elastic4s types appear in any public signature of this file.
 */
sealed trait GridEsQuery

// ─── Leaf queries ────────────────────────────────────────────────────────────

case object MatchAllQuery extends GridEsQuery
case object MatchNoneQuery extends GridEsQuery

case class MatchQuery(
  field: String,
  value: Any,
  operator: Option[GridOperator] = None
) extends GridEsQuery {
  def operator(op: GridOperator): MatchQuery = copy(operator = Some(op))
}

case class MatchPhraseQuery(field: String, value: Any) extends GridEsQuery

case class MultiMatchQuery(
  value: String,
  fields: Seq[String] = Nil,
  operator: Option[GridOperator] = None,
  matchType: Option[GridMultiMatchType] = None,
  fuzziness: Option[String] = None,
  maxExpansions: Option[Int] = None,
  prefixLength: Option[Int] = None,
  boost: Option[Double] = None
) extends GridEsQuery {
  def fields(fs: Seq[String]): MultiMatchQuery       = copy(fields = fs)
  def operator(op: GridOperator): MultiMatchQuery    = copy(operator = Some(op))
  def matchType(t: GridMultiMatchType): MultiMatchQuery = copy(matchType = Some(t))
  def fuzziness(f: String): MultiMatchQuery          = copy(fuzziness = Some(f))
  def maxExpansions(n: Int): MultiMatchQuery         = copy(maxExpansions = Some(n))
  def prefixLength(n: Int): MultiMatchQuery          = copy(prefixLength = Some(n))
  def boost(b: Double): MultiMatchQuery              = copy(boost = Some(b))
}

case class TermQuery(field: String, value: Any) extends GridEsQuery

case class TermsQuery(field: String, values: Seq[Any]) extends GridEsQuery

/** Mutable-style range builder: create with rangeQuery(field) then chain .gte/.gt/.lte/.lt */
case class RangeQuery(
  field: String,
  gte: Option[String] = None,
  gt:  Option[String] = None,
  lte: Option[String] = None,
  lt:  Option[String] = None
) extends GridEsQuery {
  def gte(v: String): RangeQuery = copy(gte = Some(v))
  def gt(v: String):  RangeQuery = copy(gt  = Some(v))
  def lte(v: String): RangeQuery = copy(lte = Some(v))
  def lt(v: String):  RangeQuery = copy(lt  = Some(v))
}

case class ExistsQuery(field: String) extends GridEsQuery

case class IdsQuery(ids: Seq[String]) extends GridEsQuery

case class PrefixQuery(field: String, prefix: String) extends GridEsQuery

case class RegexQuery(field: String, regex: String) extends GridEsQuery

case class NestedQuery(path: String, query: GridEsQuery) extends GridEsQuery

case class PinnedQuery(ids: Seq[String], organic: GridEsQuery) extends GridEsQuery

/** KNN vector query – can be used as a top-level knn parameter *or* inside a bool.should clause. */
case class GridKnnQuery(
  field: String,
  queryVector: Seq[Double] = Nil,
  k: Int = 10,
  numCandidates: Int = 100,
  filter: Option[GridEsQuery] = None,
  boost: Option[Double] = None
) extends GridEsQuery {
  def queryVector(v: Seq[Double]): GridKnnQuery  = copy(queryVector = v)
  def k(n: Int): GridKnnQuery                    = copy(k = n)
  def numCandidates(n: Int): GridKnnQuery        = copy(numCandidates = n)
  def filter(f: GridEsQuery): GridKnnQuery       = copy(filter = Some(f))
  def boost(b: Double): GridKnnQuery             = copy(boost = Some(b))
}

// ─── Compound queries ────────────────────────────────────────────────────────

case class BoolQuery(
  mustClauses:    Seq[GridEsQuery] = Nil,
  shouldClauses:  Seq[GridEsQuery] = Nil,
  mustNotClauses: Seq[GridEsQuery] = Nil,
  filterClauses:  Seq[GridEsQuery] = Nil
) extends GridEsQuery {

  // elastic4s-compatible builder methods
  def must(queries: GridEsQuery*): BoolQuery              = copy(mustClauses    = mustClauses    ++ queries)
  def must(queries: Iterable[GridEsQuery]): BoolQuery     = copy(mustClauses    = mustClauses    ++ queries)
  def should(queries: GridEsQuery*): BoolQuery            = copy(shouldClauses  = shouldClauses  ++ queries)
  def should(queries: Iterable[GridEsQuery]): BoolQuery   = copy(shouldClauses  = shouldClauses  ++ queries)
  def not(queries: GridEsQuery*): BoolQuery               = copy(mustNotClauses = mustNotClauses ++ queries)
  def not(queries: Iterable[GridEsQuery]): BoolQuery      = copy(mustNotClauses = mustNotClauses ++ queries)
  def filter(query: GridEsQuery): BoolQuery               = copy(filterClauses  = filterClauses  :+ query)
  def filter(queries: Iterable[GridEsQuery]): BoolQuery   = copy(filterClauses  = filterClauses  ++ queries)

  // Aliases matching elastic4s's withMust / withNot pattern
  def withMust(queries: GridEsQuery*): BoolQuery          = must(queries: _*)
  def withShould(queries: GridEsQuery*): BoolQuery        = should(queries: _*)
  def withNot(queries: GridEsQuery*): BoolQuery           = not(queries: _*)
  def withFilter(queries: GridEsQuery*): BoolQuery        = copy(filterClauses = filterClauses ++ queries)
}

// ─── Enumerations ────────────────────────────────────────────────────────────

sealed trait GridOperator
object GridOperator {
  case object AND extends GridOperator
  case object OR  extends GridOperator
}

sealed trait GridMultiMatchType
object GridMultiMatchType {
  case object BEST_FIELDS   extends GridMultiMatchType
  case object MOST_FIELDS   extends GridMultiMatchType
  case object CROSS_FIELDS  extends GridMultiMatchType
  case object PHRASE        extends GridMultiMatchType
  case object PHRASE_PREFIX extends GridMultiMatchType
}

sealed trait GridSortOrder
object GridSortOrder {
  case object ASC  extends GridSortOrder
  case object DESC extends GridSortOrder
}

