package com.gu.mediaservice.lib.config

import com.gu.mediaservice.lib.BaseStore
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.Json

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class MetadataStore(bucket: String, config: CommonConfig)(implicit ec: ExecutionContext)
  extends BaseStore[String, MetadataConfigClass](bucket, config)(ec) {

    def update() {
      lastUpdated.send(_ => DateTime.now())
      fetchAll match {
        case Some(config) => store.send(_ => config)
        case None => {
          println("Cannot parsess JSON *********************")
          Logger.warn("Could not parse metadata config JSON into MetadataConfig class")
        }
      }
    }

  private def fetchAll: Option[Map[String, MetadataConfigClass]] = {
    getS3Object("photographers.json" ) match {
      case Some(fileContents) => {
        Try(Json.parse(fileContents).as[MetadataConfigClass]) match {
          case Success(metadataConfigClass) => Some(Map("hello" -> metadataConfigClass))
          case Failure(_) => None
        }
      }
      case None => None
    }
  }

  def get: Future[MetadataConfigClass] = Future.successful(store.get()("hello"))
}
