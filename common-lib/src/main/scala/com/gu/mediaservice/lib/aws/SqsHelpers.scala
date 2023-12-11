package com.gu.mediaservice.lib.aws
import com.amazonaws.services.sqs.model.{Message => SQSMessage}
import com.amazonaws.services.s3.model.Bucket
import scala.util.Try
import play.api.libs.json.{Json, JsObject}
import play.api.libs.json.JsError
import play.api.libs.json.JsSuccess
import com.gu.mediaservice.lib.net.URI.{decode => uriDecode}

case class S3ObjectField (
  key: String,
  size: Int,
  eTag: String,
)

object S3ObjectField {
  implicit val S3ObjectFieldReads = Json.reads[S3ObjectField]
  implicit val S3ObjectFieldWrites = Json.writes[S3ObjectField]
}

case class S3BucketField (
  name: String,
  arn: String,
)

object S3BucketField {
  implicit val S3BucketFieldReads = Json.reads[S3BucketField]
  implicit val S3BucketFieldWrites = Json.writes[S3BucketField]
}

case class S3DataFromSqsMessage (
  s3SchemaVersion: Option[String],
  `object`: S3ObjectField,
  bucket: S3BucketField,
) {
  /**
   * @return the object key (uri decoded)
   */
  val key = uriDecode(`object`.key)

  private val keyParts = key.split("/")

  /**
   * @return the first part of the object key (uri decoded) split by slashes
  */
  val uploadedBy: String = keyParts.head

  /**
    * @return the last part of the object key (uri decoded) by slashes,
   *         expected to be a SHA-1 hash of the file if manually uploaded,
   *         else the original filename (e.g. FTP upload)
  */
  val filename: String = keyParts.last
}

object S3DataFromSqsMessage {
  implicit val S3DataReads = Json.reads[S3DataFromSqsMessage]
  implicit val MS3DatadWrites = Json.writes[S3DataFromSqsMessage]
}

case class MessageRecord (
  eventVersion: Option[String],
  eventSource: Option[String],
  eventTime: Option[String],
  eventName: Option[String],
  s3: Option[S3DataFromSqsMessage],
)

object MessageRecord {
  implicit val MessageRecordReads = Json.reads[MessageRecord]
  implicit val MessageRecordWrites = Json.writes[MessageRecord]
}

case class MessageBody(
  Records: List[MessageRecord],

)

object MessageBody {
  implicit val MessageBodyReads = Json.reads[MessageBody]
  implicit val MessageBodyWrites = Json.writes[MessageBody]
}

trait SqsHelpers {
  def getApproximateReceiveCount(message: SQSMessage): Int =
   Try(message.getAttributes().get("ApproximateReceiveCount").toInt).toOption.getOrElse(-1)

  /** Returns the time the message was first received from the queue (epoch time in milliseconds).
   * see https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_ReceiveMessage.html
  */
  def getUploadTime(message:SQSMessage):Int =
    Try(message.getAttributes().get("ApproximateFirstReceiveTimestamp").toInt).toOption.getOrElse(-1)

  def parseS3DataFromMessage(message: SQSMessage):Either[String,S3DataFromSqsMessage] = {
    Json.parse(message.getBody()).validate[MessageBody] match {
      case JsError(errors) => Left(errors.toString())
      case JsSuccess(value, path) => {
        val first = Try(value.Records.head).toOption
        first match {
          case None => Left("no records in message body")
          case Some(messageRecord) =>
            messageRecord.s3 match {
              case None => Left("first record had no S3data")
              case Some(s3data) => Right(s3data)
          }
        }
      }
    }
  }
}
