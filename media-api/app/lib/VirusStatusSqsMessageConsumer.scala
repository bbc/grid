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


  def getNotificationMsg(user: Option[String]): JsValue = {
    val messages = getMessages(waitTime = 20, maxMessages = 1)
    messages.map{ message =>
      val msg  = extractSNSMessage(message)
      println(s"Message: $msg")
      msg match {
        case Some(msg) => msg.body
        case None => Json.obj("uploadedBy" -> "None")
      }
    }.filter(m => (m \ "uploadedBy").as[String] == user.get).head
  }
}
