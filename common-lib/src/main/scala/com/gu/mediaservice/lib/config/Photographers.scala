package com.gu.mediaservice.lib.config

// TODO: Only import the semigroup syntax, but can't find out what to import
import com.gu.mediaservice.model.{ContractPhotographer, Photographer, StaffPhotographer}
import com.typesafe.config.Config
import play.api.{ConfigLoader, Configuration}

import scala.collection.JavaConverters._

case class Publication(name: String, photographers: List[String])

object Publication {
  implicit val configLoader: ConfigLoader[List[Publication]] = ConfigLoader(_.getConfigList).map(
    _.asScala.map(
      config =>
        Publication(
          config.getString("name"),
          config.getStringList("photographers").asScala.toList)).toList
  )
}

case class Photographers(
                          staffIllustrators: List[String],
                          creativeCommonsLicense: List[String],
                          externalStaffPhotographers: List[Publication],
                          internalStaffPhotographers: List[Publication],
                          contractedPhotographers: List[Publication],
                          contractIllustrators: List[Publication]) {

  val staffPhotographers: List[Publication] = Photographers.flattenPublicationList(
    internalStaffPhotographers ++ externalStaffPhotographers)

  val allPhotographers: List[Publication] = Photographers.flattenPublicationList(
    internalStaffPhotographers ++ externalStaffPhotographers ++ contractedPhotographers)

  def getPhotographer(photographer: String): Option[Photographer] = {
    caseInsensitiveLookup(staffPhotographers, photographer).map {
      case (name, publication) => StaffPhotographer(name, publication)
    }.orElse(caseInsensitiveLookup(contractedPhotographers, photographer).map {
      case (name, publication) => ContractPhotographer(name, Some(publication))
    })
  }

  def caseInsensitiveLookup(store: List[Publication], lookup: String): Option[(String, String)] =
    store.map {
      case Publication(name, photographers) if photographers.map(_.toLowerCase) contains lookup.toLowerCase() => Some(lookup, name)
      case _ => None
    }.find(_.isDefined).flatten
}

object Photographers {
  implicit val configLoader: ConfigLoader[Photographers] = ConfigLoader(_.getConfig).map(config => {
    val configuration = Configuration(config)
    Photographers(
      configuration.get[Seq[String]]("staffIllustrators").toList,
      configuration.get[Seq[String]]("creativeCommonsLicense").toList,
      configuration.get[List[Publication]]("externalStaffPhotographers"),
      configuration.get[List[Publication]]("internalStaffPhotographers"),
      configuration.get[List[Publication]]("contractedPhotographers"),
      configuration.get[List[Publication]]("contractIllustrators")
    )
  })

  def flattenPublicationList(companies: List[Publication]): List[Publication] = companies
    .groupBy(_.name)
    .map { case (group, companies) => Publication(group, companies.flatMap(company => company.photographers)) }
    .toList

}
