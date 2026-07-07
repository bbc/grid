package com.gu.mediaservice.lib.elasticsearch.client

/** Internal aggregation request types – replace com.sksamuel.elastic4s.requests.searches.aggs.* */
sealed trait GridEsAggregation {
  def name: String
}

/** Equivalent to elastic4s termsAgg(name, field) */
case class GridTermsAggregation(
  name: String,
  field: String,
  size: Int = 10,
  subAggregations: Seq[GridEsAggregation] = Nil
) extends GridEsAggregation {
  def size(n: Int): GridTermsAggregation =
    copy(size = n)
  def subAggregations(aggs: GridEsAggregation*): GridTermsAggregation =
    copy(subAggregations = aggs)
}

/** Equivalent to elastic4s topHitsAgg(name) */
case class GridTopHitsAggregation(
  name: String,
  size: Int = 3,
  fetchSource: Boolean = true
) extends GridEsAggregation {
  def size(n: Int): GridTopHitsAggregation         = copy(size = n)
  def fetchSource(b: Boolean): GridTopHitsAggregation = copy(fetchSource = b)
}

/** Equivalent to elastic4s dateHistogramAgg(name, field).calendarInterval(…).minDocCount(…).
 *  Calendar interval should be a string such as "month", "week", "day", "hour". */
case class GridDateHistogramAggregation(
  name: String,
  field: String,
  calendarInterval: Option[String] = None,
  minDocCount: Option[Long] = None
) extends GridEsAggregation {
  /** @param interval e.g. "month" (replaces DateHistogramInterval.Month) */
  def calendarInterval(interval: String): GridDateHistogramAggregation =
    copy(calendarInterval = Some(interval))
  def minDocCount(n: Long): GridDateHistogramAggregation =
    copy(minDocCount = Some(n))
}

/** Equivalent to elastic4s filterAgg(name, query).subAggregations(…) */
case class GridFilterAggregation(
  name: String,
  query: GridEsQuery,
  subAggregations: Seq[GridEsAggregation] = Nil
) extends GridEsAggregation {
  def subAggregations(aggs: GridEsAggregation*): GridFilterAggregation =
    copy(subAggregations = aggs)
  def subAggregations(maybeAgg: Option[GridEsAggregation]): GridFilterAggregation =
    copy(subAggregations = maybeAgg.toSeq)
}

