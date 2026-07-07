package com.gu.mediaservice.lib.elasticsearch

import com.gu.mediaservice.lib.ImageFields
import com.gu.mediaservice.lib.elasticsearch.client.GridEsQueryDsl._
import com.gu.mediaservice.lib.elasticsearch.client.{BoolQuery, GridEsQuery}
import com.gu.mediaservice.model._
import scalaz.NonEmptyList

object PersistedQueries extends ImageFields {

  private val photographerCategories = NonEmptyList(
    StaffPhotographer.category,
    ContractPhotographer.category,
    CommissionedPhotographer.category
  )

  private val illustratorCategories = NonEmptyList(
    ContractIllustrator.category,
    StaffIllustrator.category,
    CommissionedIllustrator.category
  )

  private val agencyCommissionedCategories = NonEmptyList(
    CommissionedAgency.category
  )

  val hasCrops: BoolQuery               = filters.bool().must(filters.existsOrMissing("exports", exists = true))
  val usedInContent: GridEsQuery        = filters.nested("usages", filters.exists(NonEmptyList("usages")))

  def hasPersistedIdentifier(ids: NonEmptyList[String]): GridEsQuery =
    filters.exists(ids.map(identifierField))

  val addedToLibrary: BoolQuery                     = filters.bool().must(filters.boolTerm(editsField("archived"), value = true))
  val hasUserEditsToImageMetadata: GridEsQuery      = filters.exists(NonEmptyList(editsField("metadata")))
  val hasPhotographerUsageRights: BoolQuery         = filters.bool().must(filters.terms(usageRightsField("category"), photographerCategories))
  val hasIllustratorUsageRights: BoolQuery          = filters.bool().must(filters.terms(usageRightsField("category"), illustratorCategories))
  val hasAgencyCommissionedUsageRights: BoolQuery   = filters.bool().must(filters.terms(usageRightsField("category"), agencyCommissionedCategories))

  def isInPersistedCollection(maybePersistOnlyTheseCollections: Option[Set[String]]): GridEsQuery =
    maybePersistOnlyTheseCollections.map(_.toList) match {
      case None =>
        filters.exists(NonEmptyList("collections"))
      case Some(Nil) => matchNoneQuery()
      case Some(head :: tail) =>
        filters.bool().must(filters.terms(collectionsField("path"), NonEmptyList.fromSeq(head, tail)))
    }

  val addedToPhotoshoot: GridEsQuery = filters.exists(NonEmptyList(editsField("photoshoot")))
  val hasLabels: GridEsQuery         = filters.exists(NonEmptyList(editsField("labels")))
  val hasLeases: GridEsQuery         = filters.exists(NonEmptyList(leasesField("leases")))
}
