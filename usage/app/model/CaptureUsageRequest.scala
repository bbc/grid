package model

import com.gu.mediaservice.model.usage.{CaptureUsageMetadata, UsageStatus}
//import com.gu.mediaservice.model.usage.{CaptureUsageMetadata, CaptureUsageStatus, UsageStatus}
import org.joda.time.DateTime
import play.api.libs.json.{JodaReads, JodaWrites, Json, Reads, Writes}

case class CaptureUsageRequest(
  dateAdded: DateTime,
  sentBy: String,
  mediaId: String
) {
  val metadata: CaptureUsageMetadata = CaptureUsageMetadata(sentBy)
//  val status: UsageStatus = CaptureUsageStatus
  println(s"XXXX capture metadata: ${metadata}")
}

object CaptureUsageRequest {
  import JodaWrites._
  import JodaReads._

  implicit val reads: Reads[CaptureUsageRequest] = Json.reads[CaptureUsageRequest]
  implicit val writes: Writes[CaptureUsageRequest] = Json.writes[CaptureUsageRequest]
}

