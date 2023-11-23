package com.gu.mediaservice.model.usage

import play.api.libs.json.{Json, Reads, Writes}

case class CaptureUsageMetadata(
  sentBy: String
) extends UsageMetadata {
  override def toMap: Map[String, Any] = Map(
    "sentBy" -> sentBy
  )
}

object CaptureUsageMetadata {
  implicit val reader: Reads[CaptureUsageMetadata] = Json.reads[CaptureUsageMetadata]
  implicit val writer: Writes[CaptureUsageMetadata] = Json.writes[CaptureUsageMetadata]
}

