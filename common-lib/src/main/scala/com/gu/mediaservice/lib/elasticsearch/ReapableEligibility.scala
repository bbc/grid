package com.gu.mediaservice.lib.elasticsearch

import com.gu.mediaservice.lib.elasticsearch.client._
import com.gu.mediaservice.lib.elasticsearch.client.GridEsQueryDsl._
import org.joda.time.DateTime
import com.gu.mediaservice.lib.config.Provider
import scalaz.NonEmptyList

import scala.concurrent.Future

trait ReapableEligibility extends Provider {

  def initialise(): Unit = {}
  def shutdown(): Future[Unit] = Future.successful(())

  val maybePersistOnlyTheseCollections: Option[Set[String]]
  val persistenceIdentifiers: NonEmptyList[String]

  private def moreThanTwentyDaysOld: GridEsQuery =
    filters.date("uploadTime", None, Some(DateTime.now().minusDays(20))).getOrElse(matchAllQuery())

  private lazy val persistedQueries: GridEsQuery = filters.or(
    PersistedQueries.hasCrops,
    PersistedQueries.usedInContent,
    PersistedQueries.addedToLibrary,
    PersistedQueries.hasUserEditsToImageMetadata,
    PersistedQueries.hasPhotographerUsageRights,
    PersistedQueries.hasIllustratorUsageRights,
    PersistedQueries.hasAgencyCommissionedUsageRights,
    PersistedQueries.addedToPhotoshoot,
    PersistedQueries.hasLabels,
    PersistedQueries.hasLeases,
    PersistedQueries.hasPersistedIdentifier(persistenceIdentifiers),
    PersistedQueries.isInPersistedCollection(maybePersistOnlyTheseCollections)
  )

  def query: GridEsQuery = filters.and(
    moreThanTwentyDaysOld,
    filters.not(persistedQueries)
  )
}
