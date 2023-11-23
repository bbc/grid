package com.gu.mediaservice.model.usage

import play.api.libs.json._

trait UsageType {
  override def toString = this match {
    case PrintUsage => "print"
    case DigitalUsage => "digital"
    case SyndicationUsage => "syndication"
    case DownloadUsage => "download"
    case CaptureUsage => "sent-to-capture"
  }
}

object UsageType {
  implicit val reads: Reads[UsageType] = JsPath.read[String].map(UsageType(_))
  implicit val writer: Writes[UsageType] = (usageType: UsageType) => JsString(usageType.toString)

  def apply(usageType: String): UsageType = usageType.toLowerCase match {
    case "print" => PrintUsage
    case "digital" => DigitalUsage
    case "syndication" => SyndicationUsage
    case "download" => DownloadUsage
    case "sent-to-capture" => CaptureUsage
  }
}

object PrintUsage extends UsageType
object DigitalUsage extends UsageType
object SyndicationUsage extends UsageType
object DownloadUsage extends UsageType
object CaptureUsage extends UsageType
