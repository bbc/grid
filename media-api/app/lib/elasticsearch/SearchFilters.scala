package lib.elasticsearch

import com.gu.mediaservice.lib.ImageFields
import com.gu.mediaservice.lib.auth.{Syndication, Tier}
import com.gu.mediaservice.lib.elasticsearch.client._
import com.gu.mediaservice.lib.elasticsearch.filters
import com.gu.mediaservice.model._
import lib.{ImagePersistenceReasons, MediaApiConfig, PersistenceReason}
import scalaz.syntax.std.list._


class SearchFilters(config: MediaApiConfig) extends ImageFields {

  val syndicationFilter = new SyndicationFilter(config)
  val usageRights = config.applicableUsageRights.toList

  val freeSuppliers = config.usageRightsConfig.freeSuppliers
  val suppliersCollectionExcl = config.usageRightsConfig.suppliersCollectionExcl

  val validFilter: GridEsQuery   = filters.exists(config.requiredMetadata.map(metadataField))
  val invalidFilter: GridEsQuery = filters.anyMissing(config.requiredMetadata.map(metadataField))

  val (suppliersWithExclusions, suppliersNoExclusions) = freeSuppliers.partition(suppliersCollectionExcl.contains)

  val suppliersWithExclusionsFilters: List[GridEsQuery] = for {
    supplier            <- suppliersWithExclusions
    excludedCollections <- suppliersCollectionExcl.get(supplier).flatMap(_.toNel.toOption)
  } yield {
    filters.mustWithMustNot(
      filters.term(usageRightsField("supplier"), supplier),
      filters.terms(usageRightsField("suppliersCollection"), excludedCollections)
    )
  }

  val suppliersWithExclusionsFilter: Option[GridEsQuery] =
    suppliersWithExclusionsFilters.toNel.map(nel => filters.or(nel.list.toList: _*)).toOption
  val suppliersNoExclusionsFilter: Option[GridEsQuery] =
    suppliersNoExclusions.toNel.map(filters.terms(usageRightsField("supplier"), _)).toOption
  val freeSupplierFilter: Option[GridEsQuery] = filterOrFilter(suppliersWithExclusionsFilter, suppliersNoExclusionsFilter)

  val freeUsageRightsFilter: Option[GridEsQuery] =
    freeToUseCategories.toNel.map(filters.terms(usageRightsField("category"), _)).toOption

  val hasRightsCategoryFilter: GridEsQuery = filters.existsOrMissing(usageRightsField("category"), exists = true)

  val freeFilter: Option[GridEsQuery]      = filterOrFilter(freeSupplierFilter, freeUsageRightsFilter)
  val nonFreeFilter: Option[GridEsQuery]   = freeFilter.map(filters.not)
  val maybeFreeFilter: Option[GridEsQuery] = filterOrFilter(freeFilter, Some(filters.not(hasRightsCategoryFilter)))

  lazy val freeToUseCategories: List[String] =
    usageRights.filter(ur => ur.defaultCost.exists(cost => cost == Free || cost == Conditional)).map(_.category)

  val persistedReasons: List[PersistenceReason] =
    ImagePersistenceReasons(config.maybePersistOnlyTheseCollections, config.persistenceIdentifiers).allReasons

  val persistedFilter: GridEsQuery    = filters.or(persistedReasons.map(_.query): _*)
  val nonPersistedFilter: GridEsQuery = filters.not(persistedFilter)

  def tierFilter(tier: Tier): Option[GridEsQuery] = tier match {
    case Syndication => Some(syndicationFilter.statusFilter(QueuedForSyndication))
    case _           => None
  }

  def printUsageFilters(printFilters: PrintUsageFilters): GridEsQuery =
    filters.nested("usages", filters.and(Seq(
      Some(filters.term("usages.status", "published")),
      Some(filters.term("usages.platform", "print")),
      Some(filters.date(
        "usages.printUsageMetadata.issueDate",
        from = printFilters.issueDate.minusSeconds(1),
        to   = printFilters.issueDate.plusDays(1)
      )),
      printFilters.sectionCode.map(filters.term("usages.printUsageMetadata.sectionCode", _)),
      printFilters.pageNumber.map(filters.term("usages.printUsageMetadata.pageNumber", _)),
      printFilters.edition.map(filters.term("usages.printUsageMetadata.edition", _)),
      printFilters.orderedBy.map(filters.term("usages.printUsageMetadata.orderedBy", _)),
    ).flatten: _*))

  def filterOrFilter(filter: Option[GridEsQuery], orFilter: Option[GridEsQuery]): Option[GridEsQuery] =
    (filter, orFilter) match {
      case (Some(f), Some(g)) => Some(filters.or(f, g))
      case (f, g)             => f orElse g
    }

  def filterAndFilter(filter: Option[GridEsQuery], andFilter: Option[GridEsQuery]): Option[GridEsQuery] =
    (filter, andFilter) match {
      case (Some(f), Some(g)) => Some(filters.and(f, g))
      case (f, g)             => f orElse g
    }
}
