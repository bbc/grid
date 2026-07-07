package lib.elasticsearch

import com.gu.mediaservice.lib.ImageFields
import com.gu.mediaservice.lib.elasticsearch.client._
import com.gu.mediaservice.lib.elasticsearch.client.GridEsQueryDsl._
import com.gu.mediaservice.lib.elasticsearch.{ReapableEligibility, filters}
import com.gu.mediaservice.model._
import lib.MediaApiConfig
import scalaz.NonEmptyList
import scalaz.syntax.std.list._

/** Internal query filter hierarchy – no elastic4s types in any public signature. */
sealed trait IsQueryFilter extends ImageFields {
  def query: GridEsQuery

  override def toString: String = this match {
    case IsOwnedPhotograph(staffPhotographerOrg)   => s"$staffPhotographerOrg-owned-photo"
    case IsOwnedIllustration(staffPhotographerOrg) => s"$staffPhotographerOrg-owned-illustration"
    case IsOwnedImage(staffPhotographerOrg)        => s"$staffPhotographerOrg-owned"
    case _: IsDeleted                              => "deleted"
    case _: IsUnderQuota                           => "under-quota"
    case _: IsReapable                             => "reapable"
    case _: IsAgencyPick                           => "agency-pick"
  }
}

object IsQueryFilter {
  def apply(value: String, overQuotaAgencies: () => List[Agency], config: MediaApiConfig): Option[IsQueryFilter] = {
    val organisation = config.staffPhotographerOrganisation.toLowerCase
    value.toLowerCase match {
      case s if s == s"$organisation-owned-photo"        => Some(IsOwnedPhotograph(organisation))
      case s if s == s"$organisation-owned-illustration" => Some(IsOwnedIllustration(organisation))
      case s if s == s"$organisation-owned"              => Some(IsOwnedImage(organisation))
      case "under-quota" => Some(IsUnderQuota(overQuotaAgencies()))
      case "deleted"     => Some(IsDeleted(true))
      case "reapable"    => Some(IsReapable(config.maybePersistOnlyTheseCollections, config.persistenceIdentifiers))
      case "agency-pick" => config.maybeAgencyPickQuery.map(IsAgencyPick)
      case _             => None
    }
  }
}

case class IsOwnedPhotograph(staffPhotographerOrg: String) extends IsQueryFilter {
  override def query: GridEsQuery = filters.or(
    filters.terms(usageRightsField("category"), UsageRights.photographer.map(_.category))
  )
}

case class IsOwnedIllustration(staffPhotographerOrg: String) extends IsQueryFilter {
  override def query: GridEsQuery = filters.or(
    filters.terms(usageRightsField("category"), UsageRights.illustrator.map(_.category))
  )
}

case class IsOwnedImage(staffPhotographerOrg: String) extends IsQueryFilter {
  override def query: GridEsQuery = filters.or(
    filters.terms(usageRightsField("category"), UsageRights.whollyOwned.map(_.category))
  )
}

case class IsUnderQuota(overQuotaAgencies: List[Agency]) extends IsQueryFilter {
  override def query: GridEsQuery = overQuotaAgencies.toNel
    .map(agency => filters.mustNot(filters.terms(usageRightsField("supplier"), agency.map(_.supplier))): GridEsQuery)
    .getOrElse(matchAllQuery())
}

case class IsDeleted(isDeleted: Boolean) extends IsQueryFilter {
  override def query: GridEsQuery =
    filters.or(filters.existsOrMissing("softDeletedMetadata", isDeleted))
}

case class IsReapable(
  maybePersistOnlyTheseCollections: Option[Set[String]],
  persistenceIdentifiers: NonEmptyList[String]
) extends IsQueryFilter with ReapableEligibility

/** Agency-pick query supplied from config.
 *  NOTE: config.maybeAgencyPickQuery must be updated to return Option[GridEsQuery].
 *  During migration use the compat shim to convert an elastic4s Query:
 *  {{{IsAgencyPick(es4sQuery.toGrid)}}}
 */
case class IsAgencyPick(query: GridEsQuery) extends IsQueryFilter
