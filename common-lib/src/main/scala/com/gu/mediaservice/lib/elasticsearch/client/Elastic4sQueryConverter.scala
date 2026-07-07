package com.gu.mediaservice.lib.elasticsearch.client

import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.requests.searches.queries.{Query => Es4sQuery}

/**
 * Standalone converter from [[GridEsQuery]] to elastic4s
 * `com.sksamuel.elastic4s.requests.searches.queries.Query`.
 *
 * Used by both [[Elastic4sGridEsClient]] internally and by the migration
 * compatibility shim.  Not part of the stable facade API.
 */
object Elastic4sQueryConverter {

  def convert(q: GridEsQuery): Es4sQuery = q match {

    case MatchAllQuery  => matchAllQuery()
    case MatchNoneQuery => matchNoneQuery()

    case q: MatchQuery =>
      val mq = matchQuery(q.field, q.value)
      q.operator.fold(mq) {
        case GridOperator.AND => mq.operator(com.sksamuel.elastic4s.requests.common.Operator.AND)
        case GridOperator.OR  => mq.operator(com.sksamuel.elastic4s.requests.common.Operator.OR)
      }

    case q: MatchPhraseQuery => matchPhraseQuery(q.field, q.value)

    case q: MultiMatchQuery =>
      import com.sksamuel.elastic4s.requests.searches.queries.matches.MultiMatchQueryBuilderType._
      var mmq = multiMatchQuery(q.value)
      if (q.fields.nonEmpty) mmq = mmq.fields(q.fields)
      q.operator.foreach {
        case GridOperator.AND => mmq = mmq.operator(com.sksamuel.elastic4s.requests.common.Operator.AND)
        case GridOperator.OR  => mmq = mmq.operator(com.sksamuel.elastic4s.requests.common.Operator.OR)
      }
      q.matchType.foreach {
        case GridMultiMatchType.BEST_FIELDS   => mmq = mmq.matchType(BEST_FIELDS)
        case GridMultiMatchType.MOST_FIELDS   => mmq = mmq.matchType(MOST_FIELDS)
        case GridMultiMatchType.CROSS_FIELDS  => mmq = mmq.matchType(CROSS_FIELDS)
        case GridMultiMatchType.PHRASE        => mmq = mmq.matchType(PHRASE)
        case GridMultiMatchType.PHRASE_PREFIX => mmq = mmq.matchType(PHRASE_PREFIX)
      }
      q.fuzziness.foreach(f => mmq = mmq.fuzziness(f))
      q.maxExpansions.foreach(n => mmq = mmq.maxExpansions(n))
      q.prefixLength.foreach(n => mmq = mmq.prefixLength(n))
      q.boost.foreach(b => mmq = mmq.boost(b.toFloat))
      mmq

    case q: TermQuery => q.value match {
      case b: Boolean => termQuery(q.field, b)
      case i: Int     => termQuery(q.field, i)
      case l: Long    => termQuery(q.field, l)
      case other      => termQuery(q.field, other.toString)
    }

    case q: TermsQuery => termsQuery(q.field, q.values.map(_.toString))

    case q: RangeQuery =>
      var rq = rangeQuery(q.field)
      q.gte.foreach(v => rq = rq.gte(v))
      q.gt.foreach(v  => rq = rq.gt(v))
      q.lte.foreach(v => rq = rq.lte(v))
      q.lt.foreach(v  => rq = rq.lt(v))
      rq

    case q: ExistsQuery => existsQuery(q.field)
    case q: IdsQuery    => idsQuery(q.ids)
    case q: PrefixQuery => prefixQuery(q.field, q.prefix)
    case q: RegexQuery  => regexQuery(q.field, q.regex)
    case q: NestedQuery => nestedQuery(q.path, convert(q.query))
    case q: PinnedQuery => pinnedQuery(ids = q.ids.toList, organic = convert(q.organic))

    // KNN inside a bool.should is handled at the search request level;
    // fall back to matchAll so it doesn't break the query structure.
    case _: GridKnnQuery => matchAllQuery()

    case q: BoolQuery =>
      var bq = boolQuery()
      if (q.mustClauses.nonEmpty)    bq = bq.must(q.mustClauses.map(convert))
      if (q.shouldClauses.nonEmpty)  bq = bq.should(q.shouldClauses.map(convert))
      if (q.mustNotClauses.nonEmpty) bq = bq.not(q.mustNotClauses.map(convert))
      if (q.filterClauses.nonEmpty)  bq = bq.filter(q.filterClauses.map(convert))
      bq
  }
}

