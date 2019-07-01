import play.api.libs.json._

val photographersJson = Json.parse("{\"companies\":[{\"name\":\"Guardian\",\"photographers\":[\"Ben Doherty\",\"Bill Code\",\"Calla Wahlquist\"]},{\"name\":\"The Observer\",\"photographers\":[\"David Newell Smith\",\"Tony McGrath\"]}]}")

val externalStaffPhotographersFromConfig = Map((for {
  company <- photographersJson("companies").as[JsArray].value
  photographer <- company("photographers").as[JsArray].value
} yield (photographer.as[String] -> company("name").as[String])): _*)


val externalStaffPhotographersFromConfig2 = Map((for {
  company <- photographersJson("companies").as[JsArray].value
} yield (company("name").as[String] -> company("photographers").as[List[String]])): _*)

val test = Map(Seq((1->2), (3->3)): _*)
println("********************")
println(externalStaffPhotographersFromConfig)
println(externalStaffPhotographersFromConfig2)


val m1 = Map((for(i <- 0 to 10; j <- 0 to 10) yield (i -> j)): _*)
val m2 = Map((for(i <- 0 to 10; j <- 0 to 10) yield (i -> j)): _*)


(m1.toSet diff m2.toSet).toMap
// photographersJson("externalStaffPhotographers")(0)("photographers").asOpt[List[String]]


//
// val nameReads: Reads[List[JsValue]] = (JsPath \ "externalStaffPhotographers").read[List[JsValue]]
//
//
////  json.validate[String] match {
////    case s: JsSuccess[String] => {
////      val names = s.get
////      println(names)
////    }
////  }
//
//println(nameReads)
//
//// Gives JSLookupresult: either JSDefined || JSUndefined
//println("Json: ", (photographersJson \ "externalStaffPhotographers")(0)("photographers"))
//
//val externalStaffPhotographersFromConfig: Seq[String] = {
//  Seq()
//}
