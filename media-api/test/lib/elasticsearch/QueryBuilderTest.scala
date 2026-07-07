package lib.elasticsearch

import com.gu.mediaservice.lib.elasticsearch.client._
import com.gu.mediaservice.model.{Agency, UsageRights}
import lib.querysyntax.Negation
import com.gu.mediaservice.lib.config.GridConfigResources
import lib.MediaApiConfig
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import play.api.inject.ApplicationLifecycle

import scala.concurrent.Future

class QueryBuilderTest extends AnyFunSpec with Matchers with ConditionFixtures with Fixtures {

  val matchFields: Seq[String] = Seq("afield", "anothermatchfield")

  private val commonConfigurations = USED_CONFIGS_IN_TEST ++ MOCK_CONFIG_KEYS.map(_ -> NOT_USED_IN_TEST).toMap

  private val mediaApiConfig = new MediaApiConfig(GridConfigResources(
    Configuration.from(commonConfigurations),
    null,
    new ApplicationLifecycle {
      override def addStopHook(hook: () => Future[_]): Unit = {}
      override def stop(): Future[_] = Future.successful(())
    }
  ))

  val queryBuilder = new QueryBuilder(matchFields, () => Nil, mediaApiConfig)

  describe("Query builder") {
    it("Nil conditions parameter should give the match all query") {
      val query = queryBuilder.makeQuery(Nil)

      query shouldBe MatchAllQuery
    }

    it("empty conditions list should give match all query") {
      val query = queryBuilder.makeQuery(List.empty)

      query shouldBe MatchAllQuery
    }

    it("single condition should give a must query") {
      val conditions = List(fieldPhraseMatchCondition)

      val query = queryBuilder.makeQuery(conditions).asInstanceOf[BoolQuery]

      query.mustClauses.size shouldBe 1
      query.mustClauses.head.asInstanceOf[MatchPhraseQuery].field shouldBe "afield"
      query.mustClauses.head.asInstanceOf[MatchPhraseQuery].value shouldBe "avalue"
    }

    it("multiple conditions should give multiple must conditions") {
      val query = queryBuilder.makeQuery(List(fieldPhraseMatchCondition, anotherFieldPhraseMatchCondition)).asInstanceOf[BoolQuery]

      query.mustClauses.size shouldBe 2
      query.mustClauses(0).asInstanceOf[MatchPhraseQuery].field shouldBe "afield"
      query.mustClauses(1).asInstanceOf[MatchPhraseQuery].field shouldBe "anotherfield"
    }

    it("negated conditions should be expressed using must not clauses") {
      val negatedCondition = Negation(fieldPhraseMatchCondition)

      val query = queryBuilder.makeQuery(List(negatedCondition)).asInstanceOf[BoolQuery]

      query.mustNotClauses.size shouldBe 1
      query.mustNotClauses.head.asInstanceOf[MatchPhraseQuery].field shouldBe "afield"
      query.mustNotClauses.head.asInstanceOf[MatchPhraseQuery].value shouldBe "avalue"
    }

    it("word list matches should set the AND operator so that all words need to match") {
      val query = queryBuilder.makeQuery(List(wordsMatchCondition)).asInstanceOf[BoolQuery]

      query.mustClauses.size shouldBe 1
      val wordsClause = query.mustClauses.head.asInstanceOf[MatchQuery]
      wordsClause.field shouldBe "awordfield"
      wordsClause.value shouldBe "foo bar"
      wordsClause.operator shouldBe Some(GridOperator.AND)
    }

    it("date ranges are expressed range queries which include the lower and upper bounds") {
      val query = queryBuilder.makeQuery(List(dateMatchCondition)).asInstanceOf[BoolQuery]

      query.mustClauses.size shouldBe 1
      val dateRangeClause = query.mustClauses.head.asInstanceOf[RangeQuery]
      dateRangeClause.gte shouldBe Some("2016-01-01T00:00:00.000Z")
      dateRangeClause.lte shouldBe Some("2016-01-01T01:00:00.000Z")
    }

    it("has field conditions are expressed as exists filters") {
      val query = queryBuilder.makeQuery(List(hasFieldCondition)).asInstanceOf[BoolQuery]

      query.mustClauses.size shouldBe 1
      val hasClause = query.mustClauses.head.asInstanceOf[BoolQuery]
      hasClause.mustClauses.size shouldBe 0
      hasClause.filterClauses.size shouldBe 1
      hasClause.filterClauses.head.asInstanceOf[ExistsQuery].field shouldBe "foo"
     }

    it("hierarchy field phrase is expressed as a term query") {
      val query = queryBuilder.makeQuery(List(hierarchyFieldPhraseCondition)).asInstanceOf[BoolQuery]

      query.mustClauses.size shouldBe 1
      query.mustClauses.head.asInstanceOf[TermQuery].value shouldBe "foo"
    }

    it("any field phrase queries should be applied to all of the match fields") {
      val query = queryBuilder.makeQuery(List(anyFieldPhraseCondition)).asInstanceOf[BoolQuery]

      query.mustClauses.size shouldBe 1
      val multiMatchClause = query.mustClauses.head.asInstanceOf[MultiMatchQuery]
      multiMatchClause.value shouldBe "cats and dogs"
      multiMatchClause.fields shouldBe matchFields
      multiMatchClause.matchType shouldBe Some(GridMultiMatchType.PHRASE)
    }

    it("any field words queries should be applied to all of the match fields with cross fields type, operator and analyzers set") {
      val query = queryBuilder.makeQuery(List(anyFieldWordsCondition)).asInstanceOf[BoolQuery]

      query.mustClauses.size shouldBe 1
      val multiMatchClause = query.mustClauses.head.asInstanceOf[MultiMatchQuery]
      multiMatchClause.value shouldBe "cats dogs"
      multiMatchClause.fields shouldBe matchFields
      multiMatchClause.operator shouldBe Some(GridOperator.AND)
      multiMatchClause.matchType shouldBe Some(GridMultiMatchType.CROSS_FIELDS)
    }

    it("any field words queries should be applied to all of the match fields with best fields type and fuzziness, operator and analyzers set") {
      val mediaApiConfigWithFuzzySearch = new MediaApiConfig(GridConfigResources(
        Configuration.from(commonConfigurations ++ Map("search.fuzziness.enabled" -> true)),
        null,
        new ApplicationLifecycle {
          override def addStopHook(hook: () => Future[_]): Unit = {}
          override def stop(): Future[_] = Future.successful(())
        }
      ))
      val queryBuilder = new QueryBuilder(matchFields, () => Nil, mediaApiConfigWithFuzzySearch)
      val query = queryBuilder.makeQuery(List(anyFieldWordsCondition)).asInstanceOf[BoolQuery]

      query.mustClauses.size shouldBe 1
      val multiMatchClause = query.mustClauses.head.asInstanceOf[MultiMatchQuery]
      multiMatchClause.value shouldBe "cats dogs"
      multiMatchClause.fields shouldBe matchFields
      multiMatchClause.operator shouldBe Some(GridOperator.AND)
      multiMatchClause.matchType shouldBe Some(GridMultiMatchType.BEST_FIELDS)
      multiMatchClause.fuzziness shouldBe defined
      multiMatchClause.fuzziness shouldBe Some("AUTO")
    }

    it("multiple field queries should query against the requested fields only") {
      val query = queryBuilder.makeQuery(List(multipleFieldWordsCondition)).asInstanceOf[BoolQuery]

      query.mustClauses.size shouldBe 1
      val multiMatchClause = query.mustClauses.head.asInstanceOf[MultiMatchQuery]
      multiMatchClause.value shouldBe "cats and dogs"
      multiMatchClause.fields shouldBe Seq("foo", "bar")
    }

    it("nested queries should be expressed using nested queries") {
      val query = queryBuilder.makeQuery(List(nestedCondition)).asInstanceOf[BoolQuery]

      query.mustClauses.size shouldBe 1
      val nestedQuery = query.mustClauses.head.asInstanceOf[NestedQuery]
      val nestedMatchQuery = nestedQuery.query.asInstanceOf[BoolQuery].mustClauses.head.asInstanceOf[MatchQuery]
      nestedMatchQuery.field shouldBe "usages.status"
      nestedMatchQuery.value shouldBe "pending"
      nestedMatchQuery.operator shouldBe Some(GridOperator.AND)
    }

    it("multiple nested queries result in multiple must clauses") {
      val query = queryBuilder.makeQuery(List(nestedCondition, anotherNestedCondition)).asInstanceOf[BoolQuery]

      query.mustClauses.size shouldBe 2
    }
  }

  describe("is search filter") {
    it("should correctly construct an is owned photo query") {
      val query = queryBuilder.makeQuery(List(isOwnedPhotoCondition)).asInstanceOf[BoolQuery]

      query.mustClauses.size shouldBe 1

      val isClause = query.mustClauses.head.asInstanceOf[BoolQuery]
      isClause.shouldClauses.size shouldBe 1

      val termQuery = isClause.shouldClauses.head.asInstanceOf[TermsQuery]
      termQuery.field shouldBe "usageRights.category"

      val expected = UsageRights.photographer.map(_.category)

      termQuery.values shouldEqual expected.list.toList
    }

    it("should correctly construct an is owned illustration query") {
      val query = queryBuilder.makeQuery(List(isOwnedIllustrationCondition)).asInstanceOf[BoolQuery]

      query.mustClauses.size shouldBe 1

      val isClause = query.mustClauses.head.asInstanceOf[BoolQuery]
      isClause.shouldClauses.size shouldBe 1

      val termQuery = isClause.shouldClauses.head.asInstanceOf[TermsQuery]
      termQuery.field shouldBe "usageRights.category"

      val expected = UsageRights.illustrator.map(_.category)

      termQuery.values shouldEqual expected.list.toList
    }

    it("should correctly construct an is owned image query") {
      val query = queryBuilder.makeQuery(List(isOwnedImageCondition)).asInstanceOf[BoolQuery]

      query.mustClauses.size shouldBe 1

      val isClause = query.mustClauses.head.asInstanceOf[BoolQuery]
      isClause.shouldClauses.size shouldBe 1

      val termQuery = isClause.shouldClauses.head.asInstanceOf[TermsQuery]
      termQuery.field shouldBe "usageRights.category"

      val expected = UsageRights.whollyOwned.map(_.category)

      termQuery.values shouldEqual expected.list.toList
    }

    it("should return the match none query on an invalid is query") {
      val query = queryBuilder.makeQuery(List(isInvalidCondition)).asInstanceOf[BoolQuery]

      query.mustClauses.size shouldBe 1
      query.mustClauses.head shouldBe MatchNoneQuery
    }

    it("should return the match all query when no agencies are over quota") {
      val qBuilder = new QueryBuilder(matchFields, () => List.empty, mediaApiConfig)
      val query = qBuilder.makeQuery(List(isUnderQuotaCondition)).asInstanceOf[BoolQuery]
      query.mustClauses.size shouldBe 1
      query.mustClauses.head shouldBe MatchAllQuery
    }

    it("should correctly construct an under quota query") {
      def overQuotaAgencies = List(Agency("Getty Images"), Agency("AP"))

      val qBuilder = new QueryBuilder(matchFields, () => overQuotaAgencies, mediaApiConfig)
      val query = qBuilder.makeQuery(List(isUnderQuotaCondition)).asInstanceOf[BoolQuery]
      query.mustClauses.size shouldBe 1

      val mustQuery = query.mustClauses.head.asInstanceOf[BoolQuery]
      mustQuery.mustNotClauses.size shouldBe 1

      val notQuery = mustQuery.mustNotClauses.head.asInstanceOf[TermsQuery]
      notQuery.field shouldBe "usageRights.supplier"

      val expected = overQuotaAgencies.map(_.supplier)
      notQuery.values shouldEqual expected
    }
  }

  describe("get elasticsearch path") {
    it("should return the field parameter itself (instead of an elasticsearchPath) if the particular config doesn't exist"){
      queryBuilder.resolveFieldPath("bbcElvisCollection") shouldBe "bbcElvisCollection"
    }

    it("should return the right elasticsearchPath"){
      queryBuilder.resolveFieldPath("credit") shouldBe "metadata.credit"
    }
  }

}
