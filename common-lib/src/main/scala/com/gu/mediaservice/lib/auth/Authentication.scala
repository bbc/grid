package com.gu.mediaservice.lib.auth

import akka.actor.ActorSystem
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.gu.mediaservice.lib.argo.ArgoHelpers
import com.gu.mediaservice.lib.argo.model.Link
import com.gu.mediaservice.lib.auth.Authentication.{Request => _, _}
import com.gu.mediaservice.lib.config.{CommonConfig, ValidEmailsStore}
import com.gu.mediaservice.lib.logging.GridLogger
import com.gu.pandomainauth.PanDomainAuthSettingsRefresher
import com.gu.pandomainauth.action.{AuthActions, UserRequest}
import com.gu.pandomainauth.model.{AuthenticatedUser, User}
import com.gu.pandomainauth.service.Google2FAGroupChecker
import play.api.Logger
import play.api.libs.ws.{DefaultWSCookie, WSClient, WSCookie}
import play.api.mvc.Security.AuthenticatedRequest
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class Authentication(config: CommonConfig, actorSystem: ActorSystem,
                     override val parser: BodyParser[AnyContent],
                     override val wsClient: WSClient,
                     override val controllerComponents: ControllerComponents,
                     override val executionContext: ExecutionContext)

  extends ActionBuilder[Authentication.Request, AnyContent] with AuthActions with ArgoHelpers {

  implicit val ec: ExecutionContext = executionContext

  val loginLinks = List(
    Link("login", config.services.loginUriTemplate)
  )

  // API key errors
  val invalidApiKeyResult = respondError(Unauthorized, "invalid-api-key", "Invalid API key provided", loginLinks)

  val keyStore = new KeyStore(config.authKeyStoreBucket, config)

  keyStore.scheduleUpdates(actorSystem.scheduler)

  val validEmailsStore = new ValidEmailsStore(config.permissionsBucket, config)

  validEmailsStore.scheduleUpdates(actorSystem.scheduler)

  private val userValidationEmailDomain = config.stringOpt("panda.userDomain").getOrElse("guardian.co.uk")

  override lazy val panDomainSettings = buildPandaSettings()

  final override def authCallbackUrl: String = s"${config.services.authBaseUri}/oauthCallback"

  override def invokeBlock[A](request: Request[A], block: Authentication.Request[A] => Future[Result]): Future[Result] = {
    // Try to auth by API key, and failing that, with Panda
    request.headers.get(Authentication.apiKeyHeaderName) match {
      case Some(key) =>
        keyStore.lookupIdentity(key) match {
          case Some(apiKey) =>
            GridLogger.info(s"Using api key with name ${apiKey.name} and tier ${apiKey.tier}", apiKey)
            if (ApiKey.hasAccess(apiKey, request, config.services))
              block(new AuthenticatedRequest(AuthenticatedService(apiKey), request))
            else
              Future.successful(ApiKey.unauthorizedResult)
          case None => Future.successful(invalidApiKeyResult)
        }
      case None =>
        APIAuthAction.invokeBlock(request, (userRequest: UserRequest[A]) => {
          block(new AuthenticatedRequest(PandaUser(userRequest.user), request))
        })
    }
  }

  final override def validateUser(authedUser: AuthenticatedUser): Boolean = {
    val validEmails = validEmailsStore.getValidEmails
    Authentication.validateUser(authedUser, userValidationEmailDomain, multifactorChecker, validEmails)
  }


  override def showUnauthedMessage(message: String)(implicit request: RequestHeader): Result = {
    Logger.info(message)
    Forbidden("You are not authorised to access The Grid, to get authorisation please email The Grid support team")
  }

  def getOnBehalfOfPrincipal(principal: Principal, originalRequest: Request[_]): OnBehalfOfPrincipal = principal match {
    case service: AuthenticatedService =>
      OnBehalfOfService(service)

    case user: PandaUser =>
      val cookieName = panDomainSettings.settings.cookieSettings.cookieName

      originalRequest.cookies.get(cookieName) match {
        case Some(cookie) => OnBehalfOfUser(user, DefaultWSCookie(cookieName, cookie.value))
        case None => throw new IllegalStateException(s"Unable to generate cookie header on behalf of ${principal.apiKey}. Missing original cookie $cookieName")
      }
  }

  private def buildPandaSettings() = {
    new PanDomainAuthSettingsRefresher(
      domain = config.services.domainRoot,
      system = config.stringOpt("panda.system").getOrElse("media-service"),
      bucketName = config.stringOpt("panda.bucketName").getOrElse("pan-domain-auth-settings"),
      settingsFileKey = config.stringOpt("panda.settingsFileKey").getOrElse(s"${config.services.domainRoot}.settings"),
      s3Client = config.withAWSCredentials(AmazonS3ClientBuilder.standard()).build()
    )
  }
}

object Authentication {
  sealed trait Principal { def apiKey: ApiKey }
  case class PandaUser(user: User) extends Principal { def apiKey: ApiKey = ApiKey(s"${user.firstName} ${user.lastName}", Internal) }
  case class AuthenticatedService(apiKey: ApiKey) extends Principal

  type Request[A] = AuthenticatedRequest[A, Principal]

  sealed trait OnBehalfOfPrincipal { def principal: Principal }
  case class OnBehalfOfUser(override val principal: PandaUser, cookie: WSCookie) extends OnBehalfOfPrincipal
  case class OnBehalfOfService(override val principal: AuthenticatedService) extends OnBehalfOfPrincipal

  val apiKeyHeaderName = "X-Gu-Media-Key"
  val originalServiceHeaderName = "X-Gu-Original-Service"

  def getEmail(principal: Principal): String = principal match {
    case PandaUser(user) => user.email
    case _ => principal.apiKey.name
  }

  def validateUser(authedUser: AuthenticatedUser, userValidationEmailDomain: String, multifactorChecker: Option[Google2FAGroupChecker], validEmails: Option[List[String]]): Boolean = {
    val isValidEmail = validEmails match {
      case Some(emails) => emails.contains(authedUser.user.email)
      case _ => false
    }
    val isValidDomain = authedUser.user.email.endsWith("@" + userValidationEmailDomain)
    val passesMultifactor = if(multifactorChecker.nonEmpty) { authedUser.multiFactor } else { true }

    isValidEmail && isValidDomain && passesMultifactor
  }
}
