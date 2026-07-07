package com.gu.mediaservice.lib.elasticsearch

import com.gu.mediaservice.lib.elasticsearch.client.{GridEsError, GridEsCausedBy}
import com.gu.mediaservice.lib.logging.{LogMarker, MarkerMap}

trait ElasticSearchError {
  self: Throwable =>
  def error: GridEsError
  def markerContents: Map[String, Any]
}

object ElasticSearchException {

  def causes(c: GridEsCausedBy): List[(String, Any)] = {
    val script = c.script.getOrElse("no script")
    List(
      "causedBy"    -> c.toString,
      "scriptStack" -> c.scriptStack.mkString("\n"),
      "script"      -> script,
      "lang"        -> "unknown"
    )
  }

  def apply(e: GridEsError): Exception with ElasticSearchError = {
    e match {
      case GridEsError(t, r, Seq(), None) =>
        new Exception(s"query failed because: $r type: $t") with ElasticSearchError {
          override def error: GridEsError = e
          override def markerContents: Map[String, Any] = Map("reason" -> r, "type" -> t)
        }
      case GridEsError(t, r, Seq(), Some(c)) =>
        new Exception(s"query failed because: $r type: $t caused by $c") with ElasticSearchError {
          override def error: GridEsError = e
          override def markerContents: Map[String, Any] = (List("reason" -> r, "type" -> t) ::: causes(c)).toMap
        }
      case GridEsError(t, r, s, None) =>
        new Exception(s"query failed because: $r type: $t root cause ${s.mkString(",\n ")}") with ElasticSearchError {
          override def error: GridEsError = e
          override def markerContents: Map[String, Any] = Map("reason" -> r, "type" -> t, "rootCause" -> s.mkString(",\n"))
        }
      case GridEsError(t, r, s, Some(c)) =>
        new Exception(s"query failed because: $r type: $t root cause ${s.mkString(", ")}, caused by $c") with ElasticSearchError {
          override def error: GridEsError = e
          override def markerContents: Map[String, Any] =
            (List("reason" -> r, "type" -> t, "rootCause" -> s.mkString(",\n"), "causedBy" -> c.toString) ::: causes(c)).toMap
        }
      case _ =>
        new Exception("query failed because: unknown error") with ElasticSearchError {
          override def error: GridEsError = e
          override def markerContents: Map[String, Any] = Map("reason" -> "unknown Elastic Search error")
        }
    }
  }

  def unapply(arg: ElasticSearchError): Option[(GridEsError, LogMarker)] =
    Some((arg.error, MarkerMap(arg.markerContents)))
}

/** Thrown when a 404 response is received and notFoundSuccessful=false. */
case object ElasticNotFoundException extends Exception("Elastic Search Document Not Found")
