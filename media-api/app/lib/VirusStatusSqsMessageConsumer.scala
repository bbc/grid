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
    val message  = extractSNSMessage(snsMessage)
    val notification = message match {
      case Some(msg) => {
        val uploadedBy = ( msg.body \ "metadata" \ "uploaded_by").as[String]
        if(uploadedBy == user){
          deleteMessage(snsMessage)
          msg.body
        } else {
          Json.obj("PENDING" -> true)
        }
      }
      case None => Json.obj("PENDING" -> true)
    }
    notification
  }
}
