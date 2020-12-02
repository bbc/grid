package lib

import com.gu.mediaservice.lib.aws.SqsMessageConsumer
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class VirusStatusSqsMessageConsumer(config: MediaApiConfig, mediaApiMetrics: MediaApiMetrics) extends SqsMessageConsumer(
  config.scannerSqsQueueUrl, config, mediaApiMetrics.sqsMessage) {

  override def chooseProcessor(subject: String): Option[JsValue => Future[Any]] =
    PartialFunction.condOpt(subject) {
      case "SLING_SCANNER_RESULT_NEGATIVE" => processNegativeImage
      case "SLING_SCANNER_RESULT_POSITIVE" => processPositiveImage
    }

  def processNegativeImage(message: JsValue) = Future {
    // emit to UI with negative status
  }

  def processPositiveImage(message: JsValue) = Future {
    // emit to UI with positive status
  }
}
