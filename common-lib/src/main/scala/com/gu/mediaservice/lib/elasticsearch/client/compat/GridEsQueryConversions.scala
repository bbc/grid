package com.gu.mediaservice.lib.elasticsearch.client.compat

import com.gu.mediaservice.lib.elasticsearch.client._
import scala.language.implicitConversions

/**
 * MIGRATION SHIM – import this only in code that has NOT yet been migrated to
 * use [[GridEsClient]] directly.
 *
 * Provides implicit conversions that allow a [[GridEsQuery]] to be used
 * wherever elastic4s expects a `com.sksamuel.elastic4s.requests.searches.queries.Query`.
 *
 * Remove this import (and the shim file) once a module is fully migrated.
 *
 * {{{
 *   import com.gu.mediaservice.lib.elasticsearch.client.compat.GridEsQueryConversions._
 * }}}
 */
object GridEsQueryConversions {

  implicit def gridQueryToEs4s(
    q: GridEsQuery
  ): com.sksamuel.elastic4s.requests.searches.queries.Query =
    Elastic4sQueryConverter.convert(q)

  implicit def gridSortToEs4s(
    s: GridSort
  ): com.sksamuel.elastic4s.requests.searches.sort.Sort =
    s match {
      case GridFieldSort(field, GridSortOrder.ASC)  =>
        com.sksamuel.elastic4s.ElasticDsl.fieldSort(field)
          .order(com.sksamuel.elastic4s.requests.searches.sort.SortOrder.ASC)
      case GridFieldSort(field, GridSortOrder.DESC) =>
        com.sksamuel.elastic4s.ElasticDsl.fieldSort(field)
          .order(com.sksamuel.elastic4s.requests.searches.sort.SortOrder.DESC)
    }

  implicit def gridRuntimeMappingToEs4s(
    rm: GridRuntimeMapping
  ): com.sksamuel.elastic4s.requests.searches.RuntimeMapping =
    com.sksamuel.elastic4s.requests.searches.RuntimeMapping(
      field = rm.field,
      `type` = rm.`type`,
      scriptSource = rm.scriptSource
    )
}

// Elastic4sQueryConverter is in the parent package and available directly.

