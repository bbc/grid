package com.gu.mediaservice.lib.play

import com.gu.mediaservice.lib.auth.Authentication
import com.gu.mediaservice.lib.config.CommonConfig
import com.gu.mediaservice.lib.management.{BuildInfo, Management}
import play.api.ApplicationLoader.Context
import play.api.BuiltInComponentsFromContext
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.EssentialFilter
import play.filters.HttpFiltersComponents
import play.filters.cors.CORSConfig.Origins
import play.filters.cors.{CORSComponents, CORSConfig}
import play.filters.gzip.GzipFilterComponents

import scala.concurrent.ExecutionContext

abstract class GridComponents(context: Context) extends BuiltInComponentsFromContext(context)
  with AhcWSComponents with HttpFiltersComponents with CORSComponents with GzipFilterComponents {

  def config: CommonConfig

  def buildInfo: BuildInfo

  implicit val ec: ExecutionContext = executionContext

  final override def httpFilters: Seq[EssentialFilter] = {
    Seq(corsFilter, csrfFilter, securityHeadersFilter, gzipFilter, new RequestLoggingFilter(materializer), new RequestMetricFilter(config, materializer))
  }

  final override lazy val corsConfig: CORSConfig = CORSConfig.fromConfiguration(context.initialConfiguration).copy(
    allowedOrigins = Origins.Matching(Set(config.services.kahunaBaseUri, config.services.apiBaseUri) ++ config.services.corsAllowedDomains)
  )

  lazy val management = new Management(controllerComponents, buildInfo)
  val auth = new Authentication(config, actorSystem, defaultBodyParser, wsClient, controllerComponents, executionContext)
}
