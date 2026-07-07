package com.gu.mediaservice.lib.elasticsearch.client

// ─── Error types ─────────────────────────────────────────────────────────────

/**
 * Internal error type – replaces com.sksamuel.elastic4s.ElasticError.
 * Carries the reason string plus optional nested cause information.
 */
case class GridEsError(
  `type`:     String,
  reason:     String,
  rootCauses: Seq[GridEsError] = Nil,
  causedBy:   Option[GridEsCausedBy] = None
) {
  def asException: Throwable = new RuntimeException(s"[${`type`}] $reason")
}

case class GridEsCausedBy(
  `type`:      String,
  reason:      String,
  scriptStack: Seq[String] = Nil,
  script:      Option[String] = None
)

/** Thrown when a document (404) is not found. */
case object GridEsNotFoundException extends Exception("OpenSearch document not found")

object GridEsError {
  val unknown: GridEsError = GridEsError(`type` = "unknown", reason = "unknown error")
}

