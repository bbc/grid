package lib.elasticsearch

import com.gu.mediaservice.lib.ImageFields
import com.gu.mediaservice.lib.elasticsearch.client._
import com.gu.mediaservice.lib.elasticsearch.client.GridEsQueryDsl._
import com.gu.mediaservice.lib.elasticsearch.filters
import com.gu.mediaservice.lib.formatting.printDateTime
import com.gu.mediaservice.lib.logging.GridLogging
import com.gu.mediaservice.model.Agency
import lib.querysyntax._
import lib.MediaApiConfig
import scalaz.NonEmptyList
import scalaz.syntax.std.list._

class QueryBuilder(matchFields: Seq[String], overQuotaAgencies: () => List[Agency], config: MediaApiConfig) extends ImageFields with GridLogging {

  def resolveFieldPath(field: String): String =
    config.fieldAliasConfigs.find(_.alias == field) match {
      case Some(x) => x.elasticsearchPath
      case None    => getFieldPath(field)
    }

  private def multiMatchPhraseQuery(value: String, fields: Seq[String]): MultiMatchQuery =
    GridEsQueryDsl.multiMatchQuery(value).fields(fields).matchType(GridMultiMatchType.PHRASE)

  private def multiMatchWordQuery(value: String, fields: Seq[String]): MultiMatchQuery = {
    val mmq = GridEsQueryDsl.multiMatchQuery(value).fields(fields).operator(GridOperator.AND)
    if (config.fuzzySearchEnabled) {
      mmq.matchType(GridMultiMatchType.BEST_FIELDS)
        .fuzziness(config.fuzzySearchEditDistance)
        .maxExpansions(config.fuzzyMaxExpansions)
        .prefixLength(config.fuzzySearchPrefixLength)
    } else {
      mmq.matchType(GridMultiMatchType.CROSS_FIELDS)
    }
  }

  private def makeMultiQuery(value: Value, fields: Seq[String]): MultiMatchQuery = value match {
    case Words(v)  => multiMatchWordQuery(v, fields)
    case Phrase(s) => multiMatchPhraseQuery(s, fields)
    case e         => throw InvalidQuery(s"Cannot do multiQuery on $e")
  }

  private def makeQueryBit(condition: Match): GridEsQuery = condition.field match {
    case AnyField               => makeMultiQuery(condition.value, matchFields)
    case MultipleField(fields)  => makeMultiQuery(condition.value, fields)
    case SingleField(field)     => condition.value match {
      case Words(value)         => matchQuery(resolveFieldPath(field), value).operator(GridOperator.AND)
      case Phrase(value)        => value match {
        case "Added to Photo Sales" => matchPhraseQuery(resolveFieldPath(field), "syndication")
        case _                      => matchPhraseQuery(resolveFieldPath(field), value)
      }
      case DateRange(start, end) =>
        rangeQuery(resolveFieldPath(field)).gte(printDateTime(start)).lte(printDateTime(end))
      case e => throw InvalidQuery(s"Cannot do single field query on $e")
    }
    case HierarchyField => condition.value match {
      case Phrase(value) => termQuery(resolveFieldPath("pathHierarchy"), value)
      case _             => throw InvalidQuery("Cannot accept non-Phrase value for HierarchyField Match")
    }
    case HasField => condition.value match {
      case HasValue(value) => boolQuery().filter(existsQuery(resolveFieldPath(value)))
      case _               => throw InvalidQuery(s"Cannot perform has field on ${condition.value}")
    }
    case IsField => condition.value match {
      case IsValue(value) => IsQueryFilter.apply(value, overQuotaAgencies, config) match {
        case Some(isQuery) => isQuery.query
        case _ =>
          logger.info(s"Cannot perform IS query on ${condition.value}")
          matchNoneQuery()
      }
      case _ =>
        logger.info(s"Cannot perform IS query on ${condition.value}")
        matchNoneQuery()
    }
    case SimilarField =>
      logger.info(s"Cannot perform SIMILAR query on ${condition.value} outside AI search mode")
      matchNoneQuery()
  }

  def makeQuery(conditions: List[Condition]): GridEsQuery = conditions match {
    case Nil => matchAllQuery()
    case condList =>
      val (nested, negationNested, normal) = (
        condList collect { case n: Nested => n },
        condList collect { case NegationNested(n) => n },
        condList collect { case c: Condition => c }
      )

      def listOfNestedToQueries(nested: List[Nested]): List[GridEsQuery] = nested
        .groupBy(_.parentField)
        .map {
          case (parent: SingleField, ns) =>
            val bq = ns.foldLeft(boolQuery()) {
              case (q, Nested(_, f, v)) => q.withMust(makeQueryBit(Match(f, v)))
              case (q, _)               => q
            }
            nestedQuery(parent.name, bq)
          case _ => throw InvalidQuery("Can only accept SingleField for Nested Query parent")
        }.toList

      val queryWithNormal = normal.foldLeft(boolQuery()) {
        case (q, Negation(cond))    => q.withNot(makeQueryBit(cond))
        case (q, cond @ Match(_, _)) => q.withMust(makeQueryBit(cond))
        case (q, _)                 => q
      }
      val queryWithNestedAndNormal =
        listOfNestedToQueries(nested).foldLeft(queryWithNormal)((q, nq) => q.withMust(nq))
      listOfNestedToQueries(negationNested).foldLeft(queryWithNestedAndNormal)((q, nq) => q.withNot(nq))
  }

  def buildFilterOpt(params: SearchParams, searchFilters: SearchFilters, syndicationFilter: SyndicationFilter): Option[GridEsQuery] = {
    val uploadTimeFilter    = filters.date("uploadTime",          params.since,         params.until)
    val lastModTimeFilter   = filters.date("lastModified",        params.modifiedSince,  params.modifiedUntil)
    val takenTimeFilter     = filters.date("metadata.dateTaken",  params.takenSince,     params.takenUntil)
    val dateFilterList      = List(uploadTimeFilter, lastModTimeFilter, takenTimeFilter).flatten.toNel
    val dateFilter          = dateFilterList.map(dateFilters => filters.and(dateFilters.list.toList: _*))

    val idsFilter           = params.ids.map(filters.ids)
    val labelFilter         = params.labels.toNel.map(filters.terms("labels", _))
    val metadataFilter      = params.hasMetadata.map(metadataField).toNel.map(filters.exists)
    val archivedFilter      = params.archived.map(filters.existsOrMissing(editsField("archived"), _))
    val hasExports          = params.hasExports.map(filters.existsOrMissing("exports", _))
    val hasIdentifier       = params.hasIdentifier.map(idName => filters.exists(NonEmptyList(identifierField(idName))))
    val missingIdentifier   = params.missingIdentifier.map(idName => filters.missing(NonEmptyList(identifierField(idName))))
    val uploadedByFilter    = params.uploadedBy.map(ub => filters.terms("uploadedBy", NonEmptyList(ub)))
    val simpleCostFilter    = params.free.flatMap(free => if (free) searchFilters.freeFilter else searchFilters.nonFreeFilter)
    val costFilter          = params.payType match {
      case Some(PayType.Free)      => searchFilters.freeFilter
      case Some(PayType.MaybeFree) => searchFilters.maybeFreeFilter
      case Some(PayType.Pay)       => searchFilters.nonFreeFilter
      case _                       => None
    }
    val printUsageFilter    = params.printUsageFilters.map(searchFilters.printUsageFilters)
    val hasRightsCategory   = params.hasRightsCategory.filter(_ == true).map(_ => searchFilters.hasRightsCategoryFilter)
    val validityFilter      = params.valid.map(v => if (v) searchFilters.validFilter else searchFilters.invalidFilter)
    val persistFilter       = params.persisted map {
      case true  => searchFilters.persistedFilter
      case false => searchFilters.nonPersistedFilter
    }
    val usageFilter: Iterable[GridEsQuery] =
      params.usageStatus.toNel.map(status => filters.terms("usagesStatus", status.map(_.toString))).toOption ++
        params.usagePlatform.toNel.map(filters.terms("usagesPlatform", _)).toOption
    val syndicationStatusFilter = params.syndicationStatus.map(status => syndicationFilter.statusFilter(status))
    val dateAddedToCollectionFilter = params.orderBy match {
      case Some("dateAddedToCollection") =>
        params.structuredQuery.flatMap {
          case Match(HierarchyField, Phrase(value)) => Some(value)
          case _ => None
        }.headOption.map(ph => termQuery("collections.pathHierarchy", ph))
      case _ => None
    }

    val allFilters: List[GridEsQuery] =
      metadataFilter.toOption.toList ++
        persistFilter ++
        labelFilter.toOption ++
        archivedFilter ++
        uploadedByFilter ++
        idsFilter ++
        validityFilter ++
        simpleCostFilter ++
        costFilter ++
        hasExports ++
        hasIdentifier ++
        missingIdentifier ++
        dateFilter.toOption ++
        usageFilter ++
        hasRightsCategory ++
        searchFilters.tierFilter(params.tier) ++
        syndicationStatusFilter ++
        dateAddedToCollectionFilter ++
        printUsageFilter

    allFilters.toNel.map(nel => nel.list.toList.reduceLeft(filters.and(_, _))).toOption
  }
}
