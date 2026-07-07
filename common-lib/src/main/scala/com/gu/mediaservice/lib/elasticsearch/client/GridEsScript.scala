package com.gu.mediaservice.lib.elasticsearch.client

/** Internal script model – replaces com.sksamuel.elastic4s.requests.script.Script */
case class GridEsScript(
  source:  String,
  lang:    String                = "painless",
  params:  Map[String, AnyRef]  = Map.empty
) {
  def lang(l: String): GridEsScript   = copy(lang = l)
  def param(key: String, value: AnyRef): GridEsScript =
    copy(params = params + (key -> value))
  def params(ps: Iterable[(String, AnyRef)]): GridEsScript =
    copy(params = params ++ ps)
  def params(ps: (String, AnyRef)*): GridEsScript =
    copy(params = params ++ ps)
}

object GridEsScript {
  /** Convenience constructor matching the elastic4s pattern:
   *  {{{Script(script = "source").lang("painless").param("k", v)}}} */
  def apply(script: String): GridEsScript = new GridEsScript(source = script)
}

/** A script field for use in search requests. */
case class GridScriptField(name: String, script: GridEsScript)

