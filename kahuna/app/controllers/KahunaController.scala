package controllers

import com.gu.mediaservice.lib.argo.ArgoHelpers
import com.gu.mediaservice.lib.auth.Authentication.Principal
import com.gu.mediaservice.lib.auth.{Authentication, Authorisation, BaseControllerWithLoginRedirects}
import lib.KahunaConfig
import play.api.mvc.ControllerComponents
import play.api.libs.json._

import scala.concurrent.ExecutionContext
import com.gu.mediaservice.lib.config.FieldAlias._
import com.gu.mediaservice.lib.config.Services
import play.api.mvc.Security.AuthenticatedRequest

class KahunaController(
  authentication: Authentication,
  val config: KahunaConfig,
  override val controllerComponents: ControllerComponents,
  authorisation: Authorisation
)(
  implicit val ec: ExecutionContext
) extends BaseControllerWithLoginRedirects with ArgoHelpers {

  override def auth: Authentication = authentication

  override def services: Services = config.services

  def index(ignored: String) = withOptionalLoginRedirect { request =>

    val maybeUser: Option[Authentication.Principal] = authentication.authenticationStatus(request).toOption

    val isIFramed = request.headers.get("Sec-Fetch-Dest").contains("iframe")

    val scriptsToLoad = config.scriptsToLoad
      .filter(_.shouldLoadWhenIFramed.contains(true) || !isIFramed)
      .filter(_.permission.map(authorisation.hasPermissionTo).fold(true)(maybeUser.exists))

    val okPath = routes.KahunaController.ok.url
    // If the auth is successful, we redirect to the kahuna domain so the iframe
    // is on the same domain and can be read by the JS
    val domainMetadataSpecs: String = Json.toJson(config.domainMetadataSpecs).toString()
    val fieldAliases: String = Json.toJson(config.fieldAliasConfigs).toString()
    val returnUri = config.rootUri + okPath
    Ok(views.html.main(
      config.mediaApiUri,
      config.authUri,
      s"${config.authUri}/login?redirectUri=$returnUri",
      config.sentryDsn,
      config.sessionId,
      config.googleTrackingId,
      config.feedbackFormLink,
      config.usageRightsHelpLink,
      config.invalidSessionHelpLink,
      config.supportEmail,
      fieldAliases,
      scriptsToLoad,
      config.staffPhotographerOrganisation,
      config.homeLinkHtml,
      config.systemName,
      config.canDownloadCrop,
      domainMetadataSpecs,
      config.recordDownloadAsUsage
    ))
  }

  def quotas = authentication { req =>
    Ok(views.html.quotas(config.mediaApiUri))
  }

  def ok = Action { implicit request =>
    Ok("ok")
  }
}
