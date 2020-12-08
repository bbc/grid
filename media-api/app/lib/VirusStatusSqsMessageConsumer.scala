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
    val snsMessage = getMessages(waitTime = 20, maxMessages = 1).head
    val msg  = extractSNSMessage(snsMessage)
    val notification = msg match {
      case Some(msg) => msg.body
      case None => Json.obj("uploadedBy" -> "None")
    }
    val uploadedBy = (notification \ "metadata" \ "uploaded_by").as[String]
    if(uploadedBy == user){
      deleteMessage(snsMessage)
      notification
    } else {
      Json.obj("scan_result" -> "scanning")
    }
  }
}
