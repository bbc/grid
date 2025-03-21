package com.gu.mediaservice.lib.config

import com.gu.mediaservice.model.{ContractPhotographer, Photographer, StaffPhotographer}
import com.typesafe.config.Config
import play.api.{ConfigLoader, Configuration}

import scala.jdk.CollectionConverters._
import scala.concurrent.Future

case class PublicationPhotographers(name: String, photographers: List[String])

object PublicationPhotographers {
  implicit val configLoader: ConfigLoader[List[PublicationPhotographers]] = ConfigLoader(_.getConfigList).map(
    _.asScala.map(
      config =>
        PublicationPhotographers(
          config.getString("name"),
          config.getStringList("photographers").asScala.toList)).toList
  )
}

sealed trait ProgrammesUsageRightsConfiguration {
  val description: Option[String] = None
}
final case class ProgrammesOrganisationOwnedConfig(override val description: Option[String] = None)
  extends ProgrammesUsageRightsConfiguration
object ProgrammesOrganisationOwnedConfig {
  implicit val configLoader: ConfigLoader[ProgrammesOrganisationOwnedConfig] = ConfigLoader(_.getConfig).map(
    config => {
      val description = if (config.hasPath("description"))
        Some(config.getString("description"))
      else
        None
      ProgrammesOrganisationOwnedConfig(description)
    }
  )
}

final case class IndependentType(name: String, productionsCompanies: List[String] = Nil)
final case class ProgrammesIndependentsConfig(override val description: Option[String] = None,
                                              independentTypes: List[IndependentType] = Nil)
  extends ProgrammesUsageRightsConfiguration
object ProgrammesIndependentsConfig {
  implicit val configLoader: ConfigLoader[ProgrammesIndependentsConfig] = ConfigLoader(_.getConfig).map(
    config => {

      val description = if (config.hasPath("description")) {
          Some(config.getString("description"))
        } else {
          None
        }

      val independentTypes = if (config.hasPath("independentTypes")) {
          config.getConfigList("independentTypes").asScala
            .map(independentTypeConfig =>
              IndependentType(
                independentTypeConfig.getString("name"),
                independentTypeConfig.getStringList("productionsCompanies").asScala.toList
              )
            ).toList
        } else {
          Nil
        }

      ProgrammesIndependentsConfig(description, independentTypes)
    }
  )
}
final case class ProgrammesAcquisitionsConfig(override val description: Option[String] = None)
  extends ProgrammesUsageRightsConfiguration
object ProgrammesAcquisitionsConfig {
  implicit val configLoader: ConfigLoader[ProgrammesAcquisitionsConfig] = ConfigLoader(_.getConfig).map(
    config => {
      val description = if (config.hasPath("description"))
        Some(config.getString("description"))
      else
        None

      ProgrammesAcquisitionsConfig(description)
    }
  )
}

trait UsageRightsConfigProvider extends Provider {
  override def initialise(): Unit = {}
  override def shutdown(): Future[Unit] = Future.successful(())

  /** By default assume that we don't do any lifecycle management */
  val externalStaffPhotographers: List[PublicationPhotographers]
  val internalStaffPhotographers: List[PublicationPhotographers]
  val contractedPhotographers: List[PublicationPhotographers]
  val contractIllustrators: List[PublicationPhotographers]
  val staffIllustrators: List[String]
  val creativeCommonsLicense: List[String]
  val freeSuppliers: List[String]
  val suppliersCollectionExcl: Map[String, List[String]]
  val programmesOrganisationOwnedConfig: Option[ProgrammesOrganisationOwnedConfig] = None
  val programmesIndependentsConfig: Option[ProgrammesIndependentsConfig] = None
  val programmesAcquisitionsConfig: Option[ProgrammesAcquisitionsConfig] = None

  // this is lazy in order to ensure it is initialised after the values above are defined
  lazy val staffPhotographers: List[PublicationPhotographers] = UsageRightsConfigProvider.flattenPublicationList(
    internalStaffPhotographers ++ externalStaffPhotographers)

  // this is lazy in order to ensure it is initialised after the values above are defined
  lazy val allPhotographers: List[PublicationPhotographers] = UsageRightsConfigProvider.flattenPublicationList(
    internalStaffPhotographers ++ externalStaffPhotographers ++ contractedPhotographers)

  def getPhotographer(photographer: String): Option[Photographer] = {
    caseInsensitiveLookup(staffPhotographers, photographer).map {
      case (name, publication) => StaffPhotographer(name, publication)
    }.orElse(caseInsensitiveLookup(contractedPhotographers, photographer).map {
      case (name, publication) => ContractPhotographer(name, Some(publication))
    })
  }

  def caseInsensitiveLookup(store: List[PublicationPhotographers], lookup: String): Option[(String, String)] =
    store.map {
      case PublicationPhotographers(name, photographers) if photographers.map(_.toLowerCase) contains lookup.toLowerCase() => Some(lookup, name)
      case _ => None
    }.find(_.isDefined).flatten
}


object UsageRightsConfigProvider {

  case class Resources()
  object ProviderLoader extends ProviderLoader[UsageRightsConfigProvider, Resources]("usage rights config")

  def flattenPublicationList(companies: List[PublicationPhotographers]): List[PublicationPhotographers] = companies
    .groupBy(_.name)
    .map { case (group, companies) => PublicationPhotographers(group, companies.flatMap(company => company.photographers)) }
    .toList
}

/** An implementation of usage rights config that can read from the configuration file */
class RuntimeUsageRightsConfig(configuration: Configuration) extends UsageRightsConfigProvider {
  val internalStaffPhotographers: List[PublicationPhotographers] = configuration.getOptional[List[PublicationPhotographers]]("internalStaffPhotographers").getOrElse(Nil)
  val externalStaffPhotographers: List[PublicationPhotographers] = configuration.getOptional[List[PublicationPhotographers]]("externalStaffPhotographers").getOrElse(Nil)
  val contractedPhotographers: List[PublicationPhotographers] = configuration.getOptional[List[PublicationPhotographers]]("contractedPhotographers").getOrElse(Nil)
  val contractIllustrators: List[PublicationPhotographers] = configuration.getOptional[List[PublicationPhotographers]]("contractIllustrators").getOrElse(Nil)
  val staffIllustrators: List[String] = configuration.getOptional[Seq[String]]("staffIllustrators").map(_.toList).getOrElse(Nil)
  val creativeCommonsLicense: List[String] = configuration.getOptional[Seq[String]]("creativeCommonsLicense").map(_.toList).getOrElse(Nil)
  val freeSuppliers: List[String] = configuration.getOptional[Seq[String]]("freeSuppliers").map(_.toList).getOrElse(Nil)
  val suppliersCollectionExcl: Map[String, List[String]] = if (configuration.has("suppliersCollectionExcl")) {
    val suppliersCollectionExclConfig: Config = configuration
      .underlying
      .getConfig("suppliersCollectionExcl")
    suppliersCollectionExclConfig
      .entrySet()
      .asScala
      .map(entry => (entry.getKey, suppliersCollectionExclConfig.getStringList(entry.getKey).asScala.toList))
      .toMap
  } else {
    Map.empty[String, List[String]]
  }
  override val programmesOrganisationOwnedConfig: Option[ProgrammesOrganisationOwnedConfig] = configuration.getOptional[ProgrammesOrganisationOwnedConfig]("programmesOrganisationOwned")
  override val programmesIndependentsConfig: Option[ProgrammesIndependentsConfig] = configuration.getOptional[ProgrammesIndependentsConfig]("programmesIndependents")
  override val programmesAcquisitionsConfig: Option[ProgrammesAcquisitionsConfig] = configuration.getOptional[ProgrammesAcquisitionsConfig]("programmesAcquisitions")
}
