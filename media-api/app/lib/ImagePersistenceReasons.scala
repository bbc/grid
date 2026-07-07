package lib

import com.gu.mediaservice.lib.elasticsearch.PersistedQueries
import com.gu.mediaservice.lib.elasticsearch.client.GridEsQuery
import com.gu.mediaservice.model.{CommissionedAgency, Illustrator, Image, ImageMetadata, Photographer}
import scalaz.NonEmptyList


case class ImagePersistenceReasons(maybePersistOnlyTheseCollections: Option[Set[String]], persistenceIdentifiers: NonEmptyList[String]) {
  val allReasons: List[PersistenceReason] =
    List(
      HasPersistenceIdentifier(persistenceIdentifiers),
      HasExports,
      HasUsages,
      IsArchived,
      IsPhotographerCategory,
      IsIllustratorCategory,
      IsAgencyCommissionedCategory,
      HasLeases,
      IsInPersistedCollection(maybePersistOnlyTheseCollections),
      AddedToPhotoshoot,
      HasLabels,
      HasUserEdits
    )

  def reasons(image: Image): List[String] = allReasons.filter(_.shouldPersist(image)).map(_.reason)
}

sealed trait PersistenceReason {
  def shouldPersist(image: Image): Boolean
  val query: GridEsQuery  // was: elastic4s Query
  val reason: String
}

case class HasPersistenceIdentifier(persistenceIdentifiers: NonEmptyList[String]) extends PersistenceReason {
  override def shouldPersist(image: Image): Boolean = persistenceIdentifiers.list.toList.exists(image.identifiers.contains)

  override val reason: String = "persistence-identifier"

  override val query: GridEsQuery = PersistedQueries.hasPersistedIdentifier(persistenceIdentifiers)
}

object HasExports extends PersistenceReason {
  override def shouldPersist(image: Image): Boolean = image.hasExports

  override val query: GridEsQuery = PersistedQueries.hasCrops

  override val reason: String = "exports"
}

object HasUsages extends PersistenceReason {
  override def shouldPersist(image: Image): Boolean = image.hasUsages

  override val query: GridEsQuery = PersistedQueries.usedInContent

  override val reason: String = "usages"
}

object IsArchived extends PersistenceReason {
  override def shouldPersist(image: Image): Boolean = image.userMetadata.exists(_.archived)

  override val query: GridEsQuery = PersistedQueries.addedToLibrary
  override val reason: String = "archived"
}

object IsPhotographerCategory extends PersistenceReason {
  override def shouldPersist(image: Image): Boolean = image.usageRights match {
    case _: Photographer => true
    case _ => false
  }

  override val query: GridEsQuery = PersistedQueries.hasPhotographerUsageRights
  override val reason: String = "photographer-category"
}

object IsIllustratorCategory extends PersistenceReason {
  override def shouldPersist(image: Image): Boolean = image.usageRights match {
    case _: Illustrator => true
    case _ => false
  }

  override val query: GridEsQuery = PersistedQueries.hasIllustratorUsageRights
  override val reason: String = "illustrator-category"
}

object IsAgencyCommissionedCategory extends PersistenceReason {
  override def shouldPersist(image: Image): Boolean = image.usageRights match {
    case _: CommissionedAgency => true
    case _ => false
  }


  override val query: GridEsQuery = PersistedQueries.hasAgencyCommissionedUsageRights
  override val reason: String = CommissionedAgency.category
}

object HasLeases extends PersistenceReason {
  override def shouldPersist(image: Image): Boolean = image.leases.leases.nonEmpty

  override val query: GridEsQuery = PersistedQueries.hasLeases
  override val reason: String = "leases"
}

case class IsInPersistedCollection(maybePersistOnlyTheseCollections: Option[Set[String]]) extends PersistenceReason {
  override def shouldPersist(image: Image): Boolean = maybePersistOnlyTheseCollections match {
    case None =>
      image.collections.nonEmpty
    case Some(persistedCollections) if persistedCollections.nonEmpty =>
      (image.collections.flatMap(_.path) intersect persistedCollections.toList).nonEmpty
    case _ => false
  }

  override val query: GridEsQuery = PersistedQueries.isInPersistedCollection(maybePersistOnlyTheseCollections)
  override val reason: String = "persisted-collection"
}

object AddedToPhotoshoot extends PersistenceReason {
  override def shouldPersist(image: Image): Boolean = image.userMetadata.exists(_.photoshoot.isDefined)

  override val query: GridEsQuery = PersistedQueries.addedToPhotoshoot
  override val reason: String = "photoshoot"
}

object HasLabels extends PersistenceReason {
  override def shouldPersist(image: Image): Boolean = image.userMetadata.exists(_.labels.nonEmpty)

  override val query: GridEsQuery = PersistedQueries.hasLabels
  override val reason: String = "labeled"
}

object HasUserEdits extends PersistenceReason {
  override def shouldPersist(image: Image): Boolean = image.userMetadata.exists(ed => ed.metadata != ImageMetadata.empty)

  override val query: GridEsQuery = PersistedQueries.hasUserEditsToImageMetadata
  override val reason: String = "edited"
}
