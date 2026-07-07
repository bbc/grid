package com.gu.mediaservice.lib.elasticsearch.client

import com.gu.mediaservice.lib.elasticsearch.filters
import com.gu.mediaservice.lib.elasticsearch.client.GridEsQueryDsl._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Validates that every category of [[GridEsQuery]] and supporting type
 * round-trips correctly through [[Elastic4sQueryConverter]] to an elastic4s
 * query.  No network is required – this is a pure unit test of type
 * conversions.
 *
 * Run with: sbt "project common-lib" "testOnly *GridEsFacadeValidationSpec"
 */
class GridEsFacadeValidationSpec extends AnyFlatSpec with Matchers {

  // ─── Helpers ────────────────────────────────────────────────────────────

  /** Convert and check we get a non-null elastic4s query back. */
  private def converts(q: GridEsQuery): Unit = {
    val es4s = Elastic4sQueryConverter.convert(q)
    es4s should not be null
  }

  // ─── Leaf queries ────────────────────────────────────────────────────────

  "MatchAllQuery" should "convert without error" in {
    converts(matchAllQuery())
  }

  "MatchNoneQuery" should "convert without error" in {
    converts(matchNoneQuery())
  }

  "MatchQuery" should "convert with and without operator" in {
    converts(matchQuery("field", "value"))
    converts(matchQuery("field", "value").operator(GridOperator.AND))
    converts(matchQuery("field", 42))
  }

  "MatchPhraseQuery" should "convert" in {
    converts(matchPhraseQuery("field", "a phrase"))
  }

  "MultiMatchQuery" should "convert with all builder options" in {
    converts(
      multiMatchQuery("needle")
        .fields(Seq("f1", "f2"))
        .operator(GridOperator.AND)
        .matchType(GridMultiMatchType.BEST_FIELDS)
        .fuzziness("AUTO")
        .maxExpansions(50)
        .prefixLength(1)
    )
    converts(
      multiMatchQuery("needle")
        .fields(Seq("f1"))
        .matchType(GridMultiMatchType.CROSS_FIELDS)
    )
    converts(
      multiMatchQuery("needle")
        .matchType(GridMultiMatchType.PHRASE)
    )
  }

  "TermQuery" should "convert Boolean, Int, and String values" in {
    converts(termQuery("field", true))
    converts(termQuery("field", false))
    converts(termQuery("field", 42))
    converts(termQuery("field", "value"))
  }

  "TermsQuery" should "convert" in {
    converts(termsQuery("field", Seq("a", "b", "c")))
  }

  "RangeQuery" should "convert all bound combinations" in {
    converts(rangeQuery("field").gte("now-5m"))
    converts(rangeQuery("field").gt("2020-01-01").lt("2021-01-01"))
    converts(rangeQuery("field").lte("2023-12-31"))
  }

  "ExistsQuery" should "convert" in {
    converts(existsQuery("someField"))
  }

  "IdsQuery" should "convert" in {
    converts(idsQuery(Seq("id1", "id2")))
  }

  "PrefixQuery" should "convert" in {
    converts(prefixQuery("id", "abc123"))
  }

  "RegexQuery" should "convert" in {
    converts(regexQuery("id", "[0-9a-f]{40}"))
  }

  "NestedQuery" should "convert recursively" in {
    converts(nestedQuery("usages", existsQuery("usages")))
    converts(nestedQuery("usages", boolQuery().must(termQuery("status", "published"))))
  }

  "PinnedQuery" should "convert" in {
    converts(pinnedQuery(Seq("id1", "id2"), organic = matchNoneQuery()))
  }

  // ─── Compound queries ────────────────────────────────────────────────────

  "BoolQuery" should "convert all clause types" in {
    converts(boolQuery()
      .must(termQuery("a", "1"))
      .should(termQuery("b", "2"))
      .not(termQuery("c", "3"))
      .filter(existsQuery("d"))
    )
    converts(boolQuery().withMust(matchAllQuery()).withNot(existsQuery("softDeleted")))
  }

  "Nested BoolQuery" should "convert" in {
    converts(
      boolQuery().must(
        boolQuery().should(matchQuery("field", "value"), termQuery("other", "x"))
      )
    )
  }

  // ─── Filters helper ──────────────────────────────────────────────────────

  "filters object" should "produce convertible queries" in {
    import com.gu.mediaservice.lib.elasticsearch.filters
    import scalaz.NonEmptyList

    converts(filters.and(existsQuery("a"), existsQuery("b")))
    converts(filters.or(existsQuery("a"), existsQuery("b")))
    converts(filters.not(existsQuery("deleted")))
    converts(filters.mustNot(existsQuery("a"), existsQuery("b")))
    converts(filters.existsOrMissing("exports", exists = true))
    converts(filters.existsOrMissing("exports", exists = false))
    converts(filters.boolTerm("archived", value = true))
    converts(filters.term("field", "value"))
    converts(filters.terms("field", NonEmptyList("a", "b")))
    converts(filters.ids(List("id1", "id2")))
    converts(filters.pinnedIds(List("id1")))
    converts(filters.nested("usages", existsQuery("usages")))
    converts(filters.mustWithMustNot(termQuery("a", "1"), existsQuery("b")))
  }

  // ─── GridEsQueryDsl factory methods ──────────────────────────────────────

  "GridEsQueryDsl.search" should "build a valid request" in {
    val req = search("myIndex")
      .query(boolQuery().must(matchAllQuery()))
      .from(0)
      .size(10)
      .sortBy(Seq(GridFieldSort("uploadTime", GridSortOrder.DESC)))
      .trackTotalHits(true)
    req.index shouldBe Seq("myIndex")
    req.query shouldBe a[BoolQuery]
    req.size shouldBe Some(10)
    req.sorts should have size 1
  }

  // ─── Script model ─────────────────────────────────────────────────────────

  "GridEsScript" should "build with chained methods" in {
    val s = GridEsScript("ctx._source.foo = params.bar")
      .lang("painless")
      .param("bar", "baz")
    s.source  shouldBe "ctx._source.foo = params.bar"
    s.lang    shouldBe "painless"
    s.params  shouldBe Map("bar" -> "baz")
  }

  // ─── Aggregation models ───────────────────────────────────────────────────

  "GridTermsAggregation" should "build" in {
    val a = termsAgg("bySupplier", "usageRights.supplier").size(25)
    a.name  shouldBe "bySupplier"
    a.field shouldBe "usageRights.supplier"
    a.size  shouldBe 25
  }

  "GridFilterAggregation" should "accept optional sub-aggregation" in {
    val sub = termsAgg("sub", "field")
    val fa  = filterAgg("myFilter", matchAllQuery()).subAggregations(Some(sub))
    fa.subAggregations should have size 1
  }

  "GridDateHistogramAggregation" should "build with interval" in {
    val a = dateHistogramAgg("byMonth", "uploadTime")
      .calendarInterval("month")
      .minDocCount(0)
    a.calendarInterval shouldBe Some("month")
    a.minDocCount      shouldBe Some(0L)
  }

  // ─── Error model ─────────────────────────────────────────────────────────

  "GridEsError" should "carry type and reason" in {
    val e = GridEsError(`type` = "index_not_found_exception", reason = "no such index [foo]")
    e.`type` shouldBe "index_not_found_exception"
    e.asException.getMessage should include("index_not_found_exception")
  }

  "GridEsNotFoundException" should "be a Throwable" in {
    GridEsNotFoundException shouldBe a[Throwable]
  }

  // ─── SearchRequest builder ────────────────────────────────────────────────

  "GridSearchRequest" should "support multi-index construction" in {
    val req = GridSearchRequest(index = Seq("idx1", "idx2"))
      .query(matchAllQuery())
      .size(100)
      .scroll(scala.concurrent.duration.Duration(60, java.util.concurrent.TimeUnit.SECONDS))
      .version(true)
      .fetchSource(false)
    req.index    shouldBe Seq("idx1", "idx2")
    req.scroll   shouldBe defined
    req.version  shouldBe true
    req.fetchSource shouldBe false
  }

  "GridSearchRequest.sortByFieldDesc" should "add a DESC sort" in {
    val req = search("index").sortByFieldDesc("uploadTime")
    req.sorts shouldBe Seq(GridFieldSort("uploadTime", GridSortOrder.DESC))
  }

  "GridSearchRequest.sortBy(Seq)" should "accept a Seq of sorts" in {
    val req = search("index").sortBy(Seq(GridFieldSort("uploadTime", GridSortOrder.DESC)))
    req.sorts shouldBe Seq(GridFieldSort("uploadTime", GridSortOrder.DESC))
  }
}

