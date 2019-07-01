package com.gu.mediaservice.lib.config

// TODO: Only import the semigroup syntax, but can't find out what to import
import java.io.File

import com.gu.mediaservice.model.{ContractPhotographer, Photographer, StaffPhotographer}
import play.api.libs.json.{JsArray, JsValue, Json}
import scalaz.Scalaz._

import scala.io.Source.fromFile

object PhotographersList {
  type Store = Map[String, String]
  type CreditBylineMap = Map[String, List[String]]

  import MetadataConfig.{contractedPhotographers, staffPhotographers}

  def creditBylineMap(store: Store): CreditBylineMap = store
      .groupBy{ case (photographer, publication) => publication }
      .map{ case (publication, photographers) => publication -> photographers.keys.toList.sortWith(_.toLowerCase < _.toLowerCase) }

  def creditBylineMap(stores: List[Store]): CreditBylineMap =
    stores.map(creditBylineMap).reduceLeft(_ |+| _)

  def list(store: Store) = store.keys.toList.sortWith(_.toLowerCase < _.toLowerCase)

  def getPublication(store: Store, name: String): Option[String] = store.get(name)

  def caseInsensitiveLookup(store: Store, lookup: String) =
    store.find{case (name, pub) => name.toLowerCase == lookup.toLowerCase}

  def getPhotographer(photographer: String): Option[Photographer] = {
    caseInsensitiveLookup(staffPhotographers, photographer).map {
      case (name, pub) => StaffPhotographer(name, pub)
    }.orElse(caseInsensitiveLookup(contractedPhotographers, photographer).map {
      case (name, pub) => ContractPhotographer(name, Some(pub))
    })
  }

  // Function to replicate the hardcoded maps name -> credit
  def getStaffFromJsonMap(json: JsValue, lookup: String): Store = Map((for {
    company <- json(lookup).as[JsArray].value
    photographer <- company("photographers").as[JsArray].value
  } yield photographer.as[String] -> company("name").as[String]): _*)

  // Alternate to create Map(credit1 -> List(name), credit2 -> List(name), ...) from json
  def creditBylineMap(json: JsValue, lookup: String): CreditBylineMap = Map((for {
    company <- json(lookup).as[JsArray].value
  } yield company("name").as[String] -> company("photographers").as[List[String]].sortWith(_.toLowerCase < _.toLowerCase)): _*)
}

object MetadataConfig {

  // mimicking BaseStore.scala
  //   val s3 = new S3(this)
  //  val content = s3.client.getObject("media-service-dev-configbucket-samn143r8mpp", "photographers.json")

  val file = new File("/Users/stephf02/Development/source/grid/common-lib/src/main/scala/com/gu/mediaservice/lib/config/photographers.json")
  val photographers: String = if (file.exists) fromFile(file).mkString.trim else ""
  val jsonConfig: JsValue = Json.parse(photographers)

  val externalStaffPhotographersJ: Map[String, String] = PhotographersList.getStaffFromJsonMap(jsonConfig, "externalStaffPhotographers")
  val internalStaffPhotographersJ: Map[String, String] = PhotographersList.getStaffFromJsonMap(jsonConfig, "internalStaffPhotographers")
  val contractedPhotographersJ: Map[String, String] = PhotographersList.getStaffFromJsonMap(jsonConfig, "contractedPhotographers")

  val staffPhotographersJ: Map[String, String] = externalStaffPhotographersJ ++ internalStaffPhotographersJ
  val allPhotographersJ: Map[String, String] = staffPhotographersJ ++ contractedPhotographersJ

  val externalPhotographersMapJ: Map[String, List[String]] = PhotographersList.creditBylineMap(jsonConfig, "externalStaffPhotographers")
  val staffPhotographersMapJ: Map[String, List[String]] = PhotographersList.creditBylineMap(staffPhotographersJ)
  val allPhotographersMapJ: Map[String, List[String]] = PhotographersList.creditBylineMap(allPhotographersJ)
  val contractPhotographersMapJ: Map[String, List[String]] = PhotographersList.creditBylineMap(jsonConfig, "contractedPhotographers")
  val contractIllustratorsMapJ: Map[String, List[String]] = PhotographersList.creditBylineMap(jsonConfig, "contractIllustrators")

  val staffIllustratorsJ: List[String] = jsonConfig("staffIllustrators").as[List[String]]
  val creativeCommonsLicenseJ: List[String] = jsonConfig("creativeCommonsLicense").as[List[String]]

  val externalStaffPhotographers: Map[String, String] = Map(
    // Current
    "Ben Doherty"     -> "The Guardian",
    "Bill Code"       -> "The Guardian",
    "Calla Wahlquist" -> "The Guardian",
    "David Sillitoe"  -> "The Guardian",
    "Graham Turner"   -> "The Guardian",
    "Helen Davidson"  -> "The Guardian",
    "Jill Mead"       -> "The Guardian",
    "Jonny Weeks"     -> "The Guardian",
    "Joshua Robertson" -> "The Guardian",
    "Rachel Vere"     -> "The Guardian",
    "Roger Tooth"     -> "The Guardian",
    "Sean Smith"      -> "The Guardian",
    "Melissa Davey"   -> "The Guardian",
    "Michael Safi"    -> "The Guardian",
    "Michael Slezak"  -> "The Guardian",
    "Sean Smith"      -> "The Guardian",
    "Carly Earl"      -> "The Guardian",

    // Past
    "Dan Chung"             -> "The Guardian",
    "Denis Thorpe"          -> "The Guardian",
    "Don McPhee"            -> "The Guardian",
    "Frank Baron"           -> "The Guardian",
    "Frank Martin"          -> "The Guardian",
    "Garry Weaser"          -> "The Guardian",
    "Graham Finlayson"      -> "The Guardian",
    "Martin Argles"         -> "The Guardian",
    "Peter Johns"           -> "The Guardian",
    "Robert Smithies"       -> "The Guardian",
    "Tom Stuttard"          -> "The Guardian",
    "Tricia De Courcy Ling" -> "The Guardian",
    "Walter Doughty"        -> "The Guardian",
    "David Newell Smith"    -> "The Observer",
    "Tony McGrath"          -> "The Observer",
    "Catherine Shaw"        -> "The Observer",
    "John Reardon"          -> "The Observer",
    "Sean Gibson"           -> "The Observer"
  )

  // these are people who aren't photographers by trade, but have taken photographs for us.
  // This is mainly used so when we ingest photos from Picdar, we make sure we categorise
  // them correctly.
  // TODO: Think about removin these once Picdar is dead.
  val internalStaffPhotographers = Map(
    "E Hamilton West"       -> "The Guardian",
    "Harriet St Johnston"   -> "The Guardian",
    "Lorna Roach"           -> "The Guardian",
    "Rachel Vere"           -> "The Guardian",
    "Ken Saunders"          -> "The Guardian"
  )

  val staffPhotographers = externalStaffPhotographers ++ internalStaffPhotographers

  val contractedPhotographers: Map[String, String] = Map(
    "Alicia Canter"       -> "The Guardian",
    "Antonio Zazueta"     -> "The Guardian",
    "Christopher Thomond" -> "The Guardian",
    "David Levene"        -> "The Guardian",
    "Eamonn McCabe"       -> "The Guardian",
    "Graeme Robertson"    -> "The Guardian",
    "Johanna Parkin"      -> "The Guardian",
    "Linda Nylind"        -> "The Guardian",
    "Louise Hagger"       -> "The Guardian",
    "Martin Godwin"       -> "The Guardian",
    "Mike Bowers"         -> "The Guardian",
    "Murdo MacLeod"       -> "The Guardian",
    "Sarah Lee"           -> "The Guardian",
    "Tom Jenkins"         -> "The Guardian",
    "Tristram Kenton"     -> "The Guardian",
    "Jill Mead"           -> "The Guardian",

    "Andy Hall"           -> "The Observer",
    "Antonio Olmos"       -> "The Observer",
    "Gary Calton"         -> "The Observer",
    "Jane Bown"           -> "The Observer",
    "Jonathan Lovekin"    -> "The Observer",
    "Karen Robinson"      -> "The Observer",
    "Katherine Anne Rose" -> "The Observer",
    "Richard Saker"       -> "The Observer",
    "Sophia Evans"        -> "The Observer",
    "Suki Dhanda"         -> "The Observer"
  )

  val staffIllustrators = List(
    "Mona Chalabi",
    "Sam Morris",
    "Guardian Design"
  )

  val contractIllustrators: Map[String, String] = Map(
    "Ben Lamb"              -> "The Guardian",
    "Andrzej Krauze"        -> "The Guardian",
    "David Squires"         -> "The Guardian",
    "First Dog on the Moon" -> "The Guardian",
    "Harry Venning"         -> "The Guardian",
    "Martin Rowson"         -> "The Guardian",
    "Matt Kenyon"           -> "The Guardian",
    "Matthew Blease"        -> "The Guardian",
    "Nicola Jennings"       -> "The Guardian",
    "Rosalind Asquith"      -> "The Guardian",
    "Steve Bell"            -> "The Guardian",
    "Steven Appleby"        -> "The Guardian",
    "Ben Jennings"          -> "The Guardian",
    "Chris Riddell"         -> "The Observer",
    "David Foldvari"        -> "The Observer",
    "David Simonds"         -> "The Observer",
  )

  val allPhotographers = staffPhotographers ++ contractedPhotographers
  val externalPhotographersMap = PhotographersList.creditBylineMap(externalStaffPhotographers)
  val staffPhotographersMap = PhotographersList.creditBylineMap(staffPhotographers)
  val contractPhotographersMap = PhotographersList.creditBylineMap(contractedPhotographers)
  val allPhotographersMap = PhotographersList.creditBylineMap(allPhotographers)
  val contractIllustratorsMap = PhotographersList.creditBylineMap(contractIllustrators)


//  println(externalStaffPhotographers == externalStaffPhotographersJ)
//  println(internalStaffPhotographers == internalStaffPhotographersJ) // for some reason the hard coded values are a list not a map
//  println(allPhotographers == allPhotographersJ)
//  println(externalPhotographersMap == externalPhotographersMapJ)
//  println("**********************")
//  println(externalPhotographersMap)
//  println(externalPhotographersMapJ)
//  println(staffPhotographersMap == staffPhotographersMapJ)
//  println(contractPhotographersMap == contractPhotographersMapJ)
//  println(allPhotographersMap == allPhotographersMapJ)
//  println(contractIllustratorsMap == contractIllustratorsMapJ)


  val creativeCommonsLicense = List(
    "CC BY-4.0", "CC BY-SA-4.0", "CC BY-ND-4.0"
  )
}
