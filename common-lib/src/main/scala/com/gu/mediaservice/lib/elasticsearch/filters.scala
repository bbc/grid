package com.gu.mediaservice.lib.elasticsearch

import com.gu.mediaservice.lib.elasticsearch.client._
import com.gu.mediaservice.lib.elasticsearch.client.GridEsQueryDsl._
import com.gu.mediaservice.lib.formatting.printDateTime
import org.joda.time.DateTime
import scalaz.NonEmptyList
import scalaz.syntax.foldable1._

object filters {

  def and(queries: GridEsQuery*): GridEsQuery = must(queries: _*)

  def or(queries: GridEsQuery*): GridEsQuery = should(queries: _*)

  def or(queries: NonEmptyList[GridEsQuery]): GridEsQuery = should(queries.toList: _*)

  def boolTerm(field: String, value: Boolean): TermQuery = termQuery(field, value)

  /**
   * Range query based on dates
   * @param field Field name to query
   * @param from  Lower bound for date (exclusive)
   * @param to    Upper bound for date (exclusive)
   * @return Suitable query
   */
  def date(field: String, from: DateTime, to: DateTime): GridEsQuery =
    rangeQuery(field).gt(printDateTime(from)).lt(printDateTime(to))

  /**
   * Range query based on dates – handles optional to and from
   * @param field Field name to query
   * @param from  Lower bound for date (exclusive)
   * @param to    Upper bound for date (exclusive)
   * @return Suitable query if at least one of `from` and `to` is defined; otherwise nothing
   */
  def date(field: String, from: Option[DateTime], to: Option[DateTime]): Option[GridEsQuery] =
    if (from.isDefined || to.isDefined) {
      val builder = rangeQuery(field)
      val withFrom = from.fold(builder)(f => builder.gt(printDateTime(f)))
      Some(to.fold(withFrom)(t => withFrom.lt(printDateTime(t))))
    } else {
      None
    }

  def exists(fields: NonEmptyList[String]): GridEsQuery =
    fields.map(f => existsQuery(f): GridEsQuery).foldRight1(and(_, _))

  def missing(fields: NonEmptyList[String]): GridEsQuery =
    fields.map(f => not(existsQuery(f)): GridEsQuery).foldRight1(and(_, _))

  def ids(idList: List[String]): GridEsQuery = idsQuery(idList)

  def pinnedIds(idList: List[String]): GridEsQuery = pinnedQuery(ids = idList, organic = matchNoneQuery())

  def bool(): BoolQuery = BoolQuery()

  def mustNot(queries: GridEsQuery*): BoolQuery = BoolQuery().withNot(queries: _*)

  def term(field: String, t: String): TermQuery  = termQuery(field, t)
  def term(field: String, t: Int): TermQuery     = termQuery(field, t)

  def terms(field: String, ts: NonEmptyList[String]): GridEsQuery =
    termsQuery(field, ts.list.toList)

  def terms(field: String, ts: Iterable[String]): GridEsQuery =
    termsQuery(field, ts.toSeq)

  def existsOrMissing(field: String, exists: Boolean): GridEsQuery =
    if (exists) existsQuery(field)
    else not(existsQuery(field))

  def anyMissing(fields: NonEmptyList[String]): GridEsQuery =
    fields.map(f => not(existsQuery(f)): GridEsQuery).foldRight1(or(_, _))

  def not(filter: GridEsQuery): BoolQuery = BoolQuery().withNot(filter)

  def mustWithMustNot(mustClause: GridEsQuery, mustNotClause: GridEsQuery): BoolQuery =
    bool().must(mustClause).withNot(mustNotClause)

  def nested(path: String, query: GridEsQuery): NestedQuery = nestedQuery(path, query)
}
