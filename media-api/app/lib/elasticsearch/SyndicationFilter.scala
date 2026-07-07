package lib.elasticsearch

import com.gu.mediaservice.lib.ImageFields
import com.gu.mediaservice.lib.elasticsearch.client._
import com.gu.mediaservice.lib.elasticsearch.client.GridEsQueryDsl._
import com.gu.mediaservice.lib.elasticsearch.filters
import com.gu.mediaservice.model._
import com.gu.mediaservice.model.leases.{AllowSyndicationLease, DenySyndicationLease}
import com.gu.mediaservice.model.usage.SyndicationUsage
import lib.MediaApiConfig
import org.joda.time.DateTime

class SyndicationFilter(config: MediaApiConfig) extends ImageFields {

  val isSyndicationDateFilterActive: Boolean = config.isProd

  val hasActiveDeny: TermQuery = filters.boolTerm("hasActiveDenySyndicationLease", value = true)

  /** Runtime mapping – replaces elastic4s RuntimeMapping */
  lazy val syndicationReviewQueueFixMapping: GridRuntimeMapping = GridRuntimeMapping(
    field = hasActiveDeny.field,
    `type` = "boolean",
    scriptSource =
      """
         |long nowInMillis = new Date().getTime();
         |if (params['_source'].leases == null || params['_source'].leases.leases == null) {
         |    emit(false); return;
         |}
         |for (lease in params['_source'].leases.leases) {
         |    if (lease.access == 'deny-syndication' && (lease.endDate == null || ZonedDateTime.parse(lease.endDate).toInstant().toEpochMilli() > nowInMillis)) {
         |        emit(true); return;
         |    }
         |}
         |emit(false);
         |""".stripMargin
  )

  private def syndicationRightsAcquired(acquired: Boolean): TermQuery = filters.boolTerm(
    field = "syndicationRights.rights.acquired",
    value = acquired
  )

  private val noRightsAcquired: GridEsQuery = filters.or(
    filters.existsOrMissing("syndicationRights.rights.acquired", exists = false),
    syndicationRightsAcquired(false)
  )

  private val hasRightsAcquired: TermQuery = syndicationRightsAcquired(true)

  private val hasAllowLease: TermQuery = filters.term(
    "leases.leases.access",
    AllowSyndicationLease.name
  )

  private val hasDenyLease: TermQuery = filters.term(
    "leases.leases.access",
    DenySyndicationLease.name
  )


  private val hasSyndicationUsage: TermQuery = filters.term(
    "usagesPlatform",
    SyndicationUsage.toString
  )

  private def leaseHasStarted: GridEsQuery = filters.or(
    filters.existsOrMissing("leases.leases.startDate", exists = false),
    filters.date("leases.leases.startDate", None, Some(DateTime.now)).get
  )

  private def leaseHasNotExpired: GridEsQuery = filters.or(
    filters.existsOrMissing("leases.leases.endDate", exists = false),
    filters.date("leases.leases.endDate", Some(DateTime.now), None).get
  )

  private def syndicationRightsPublished: GridEsQuery = filters.or(
    filters.existsOrMissing("syndicationRights.published", exists = false),
    filters.date("syndicationRights.published", None, Some(DateTime.now)).get
  )

  private val syndicatableCategory: GridEsQuery = IsOwnedPhotograph(config.staffPhotographerOrganisation).query

  def statusFilter(status: SyndicationStatus): GridEsQuery = status match {
    case SentForSyndication => filters.and(
      hasRightsAcquired,
      hasAllowLease,
      hasSyndicationUsage
    )
    case QueuedForSyndication => filters.and(
      hasRightsAcquired,
      filters.mustNot(hasSyndicationUsage),
      filters.and(
        hasAllowLease,
        leaseHasStarted,
        syndicationRightsPublished
      )
    )
    case BlockedForSyndication => filters.and(
      hasRightsAcquired,
      hasDenyLease
    )
    case AwaitingReviewForSyndication =>
      val mustNotClauses = List(
        hasAllowLease,
        filters.and(hasDenyLease, leaseHasNotExpired),
      ) ++ (
        if (config.useRuntimeFieldsToFixSyndicationReviewQueueQuery) List(hasActiveDeny)
        else Nil
      )

      val rightsAcquiredNoLeaseFilter = filters.and(
        hasRightsAcquired,
        syndicatableCategory,
        filters.mustNot(mustNotClauses: _*)
      )

      config.syndicationStartDate match {
        case Some(date) if config.isProd => filters.and(
          filters.date("uploadTime", Some(date), None).get,
          rightsAcquiredNoLeaseFilter
        )
        case _ => rightsAcquiredNoLeaseFilter
      }
    case UnsuitableForSyndication => noRightsAcquired
  }
}
