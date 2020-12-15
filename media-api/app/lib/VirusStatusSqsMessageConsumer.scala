package lib

import com.gu.mediaservice.lib.aws.SqsMessageConsumer
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class VirusStatusSqsMessageConsumer(config: MediaApiConfig, mediaApiMetrics: MediaApiMetrics) extends SqsMessageConsumer(
  config.scannerSqsQueueUrl, config, mediaApiMetrics.sqsMessage) {

  override def chooseProcessor(subject: String): Option[JsValue => Future[Any]] =
    PartialFunction.condOpt(subject) {
      case _ => JsValue => Future{}
    }


  def getNotificationMsg(user: String): JsValue = {
    getMessages(waitTime = 20, maxMessages = 1) match {
      case message::Nil => {
        extractSNSMessage(message) match {
          case Some(msg) if ( msg.body \ "metadata" \ "uploaded_by").as[String] == user => {
            deleteMessage(message)
            msg.body
          }
          case _ => Json.obj("PENDING" -> true)
        }
      }
      case Nil =>  Json.obj("PENDING" -> true)
    }
  }
}
