package com.gu.mediaservice.model.usage

import play.api.libs.json.{Json, Reads, Writes}

case class IntegrationUsageMetadata(
  integrationTool: String
) extends UsageMetadata {
  override def toMap: Map[String, Any] = Map(
    "integrationTool" -> integrationTool
  )
}

object IntegrationUsageMetadata {
  implicit val reader: Reads[IntegrationUsageMetadata] = Json.reads[IntegrationUsageMetadata]
  implicit val writer: Writes[IntegrationUsageMetadata] = Json.writes[IntegrationUsageMetadata]
}