package bbc.lib.auth

import java.nio.charset.StandardCharsets
import java.util.Base64

import bbc.lib.auth.BBCPPProxyAuthenticationProvider.CKNSSessionIsFromLogin
import com.gu.mediaservice.lib.argo.ArgoHelpers
import com.gu.mediaservice.lib.auth.Authentication
import com.gu.mediaservice.lib.auth.Authentication.UserPrincipal
import com.gu.mediaservice.lib.auth.provider.AuthenticationProvider.RedirectUri
import com.gu.mediaservice.lib.auth.provider.{Authenticated, AuthenticationProviderResources, AuthenticationStatus, Expired, NotAuthenticated, UserAuthenticationProvider}
import com.typesafe.scalalogging.StrictLogging
import io.jsonwebtoken.impl.crypto.JwtSignatureValidator
import play.api.Configuration
import play.api.http.HeaderNames
import play.api.libs.json.{JsValue, Json}
import play.api.libs.typedmap.{TypedEntry, TypedKey, TypedMap}
import play.api.libs.ws.{DefaultWSCookie, WSClient, WSRequest}
import play.api.mvc.{ControllerComponents, Cookie, DiscardingCookie, RequestHeader, Result}
import play.utils.{InvalidUriEncodingException, UriEncoding}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try




class BBCPPProxyAuthenticationProvider (resources: AuthenticationProviderResources, providerConfiguration: Configuration)
  extends UserAuthenticationProvider with StrictLogging with ArgoHelpers with HeaderNames {

  implicit val ec: ExecutionContext = resources.controllerComponents.executionContext
  private val defaultMaxAge = 60*60*4
  private val ppRedirectURI = providerConfiguration.getOptional[String]("app.loginURI").getOrElse(s"${resources.commonConfig.services.authBaseUri}/login")
  private val ppRedirectLogoutURI = providerConfiguration.getOptional[String]("app.logoutURI").getOrElse(s"${resources.commonConfig.services.authBaseUri}/logout")
  private val emailHeaderKey = providerConfiguration.getOptional[String]("pp.header.email").getOrElse("bbc-pp-oidc-id-token-email")
  private val idTokenHeaderKey = providerConfiguration.getOptional[String]("pp.header.idtoken").getOrElse("bbc-pp-oidc-id-token")
  private val expiryHeaderKey = providerConfiguration.getOptional[String]("pp.header.expiry").getOrElse("bbc-pp-oidc-id-token-expiry")
  private val ppProxyCookieName = providerConfiguration.getOptional[String]("pp.cookie").getOrElse("ckns_pp_id")
  private val ppProxySessionCookie = providerConfiguration.getOptional[String]("pp.session_cookie").getOrElse("ckns_pp_session")
  private val extraCookieName = providerConfiguration.getOptional[String]("pp.extracookie.name").getOrElse("pp-grid-auth")
  private val extraCookieEnabled = providerConfiguration.getOptional[Boolean]("pp.extracookie.enabled").getOrElse(true)
  private val extraCookieDomain = providerConfiguration.getOptional[String]("pp.extracookie.domain").getOrElse(".images.int.tools.bbc.co.uk")
  private val maxAge = providerConfiguration.getOptional[Int]("pp.extracookie.maxage").getOrElse(defaultMaxAge)
  private val kahunaBaseURI: String = resources.commonConfig.services.kahunaBaseUri

  val ppProxyCookieKey: TypedKey[Cookie] = TypedKey[Cookie](ppProxyCookieName)
  val extraCookieKey: TypedKey[Cookie] = TypedKey[Cookie](extraCookieName)
  /**
    * Establish the authentication status of the given request header. This can return an authenticated user or a number
    * of reasons why a user is not authenticated.
    *
    * @param request The request header containing cookies and other request headers that can be used to establish the
    *                authentication status of a request.
    * @return An authentication status expressing whether the
    */
  override def authenticateRequest(request: RequestHeader): AuthenticationStatus = {

    val tryAuth = for {
      bbcUser <- getBBCUser(request) if checkRequestValidity(request) == Valid
    } yield Authenticated(gridUserFrom(request, bbcUser))

    tryAuth.getOrElse(NotAuthenticated)
  }

  /**
    * If this provider supports sending a user that is not authorised to a federated auth provider then it should
    * provide a function here to redirect the user. The function signature takes the the request and returns a result
    * which is likely a redirect to an external authentication system.
    */
  override def sendForAuthentication: Option[RequestHeader => Future[Result]] = Some({requestHeader: RequestHeader =>
    authenticateRequest(requestHeader) match {
      case Authenticated(_) => Future(redirectToSource(requestHeader))
      case _ => Future(redirectToPP())
    }
  })

  /**
    * If this provider supports sending a user that is not authorised to a federated auth provider then it should
    * provide a function here that deals with the return of a user from a federated provider. This should be
    * used to set a cookie or similar to ensure that a subsequent call to authenticateRequest will succeed. If
    * authentication failed then this should return an appropriate 4xx result.
    * The function should take the Play request header and the redirect URI that the user should be
    * sent to on successful completion of the authentication.
    */
  override def sendForAuthenticationCallback: Option[(RequestHeader, Option[RedirectUri]) => Future[Result]] = {
    sendForAuthentication.map(sendForAuth => (requestHeader: RequestHeader, _: Option[RedirectUri]) => {
      val ppSessionCookie = requestHeader.cookies.get(ppProxySessionCookie)
      val isCKNSSessionFromLogin = ppSessionCookie.exists(cookie => CKNSSessionIsFromLogin(cookie.value))
      if(isCKNSSessionFromLogin) {
        sendForAuth(requestHeader)
      } else Future(Redirect(kahunaBaseURI))
    })
  }

  /**
    * If this provider is able to clear user tokens (i.e. by clearing cookies) then it should provide a function to
    * do that here which will be used to log users out and also if the token is invalid.
    * This function takes the request header and a result to modify and returns the modified result.
    */
  override def flushToken: Option[(RequestHeader, Result) => Result] = Some({(header, result) =>
    Redirect(ppRedirectLogoutURI).discardingCookies(DiscardingCookie(extraCookieName, "/", Some(extraCookieDomain)))
  })

  /**
    * A function that allows downstream API calls to be made using the credentials of the current principal.
    * It is recommended that any data required for this downstream request enrichment is put into the principal's
    * attribute map when the principal is created in the authenticateRequest call.
    *
    * @param principal The principal for the current request
    * @return Either a function that adds appropriate authentication headers to a WSRequest or an error string explaining
    *         why it wasn't possible to create a function.
    */
  override def onBehalfOf(request: Authentication.Principal): Either[String, WSRequest => WSRequest] = {
    val cookieName = if (extraCookieEnabled) extraCookieName else ppProxyCookieName
    request.attributes.get(extraCookieKey) match {
      case Some(cookie) => Right { wsRequest: WSRequest =>
        wsRequest.addCookies(DefaultWSCookie(cookie.name, cookie.value))
      }
      case None => Left(s"Cookie $cookieName is missing in principal.")
    }
  }

  private def redirectToPP() = {
    Redirect(ppRedirectURI)
  }

  private def redirectToSource(request: RequestHeader) = {
    val redirect = request.getQueryString("redirectUri").map(redirectURL => Redirect(redirectURL)).getOrElse(Redirect(kahunaBaseURI))
    if(extraCookieEnabled) {
      redirect.withCookies(generateGridCookie(request))
    } else {
      redirect
    }
  }

  private def gridUserFrom(request: RequestHeader, bbcUser: BBCBasicUserInfo): UserPrincipal = {
    val maybePPProxyCookie: Option[TypedEntry[Cookie]] = request.cookies.get(ppProxyCookieName).map(TypedEntry[Cookie](ppProxyCookieKey, _))
    val maybeExtraCookie: Option[TypedEntry[Cookie]] = request.cookies.get(extraCookieName).map(TypedEntry[Cookie](extraCookieKey, _))
    val attributes = TypedMap.empty + (maybePPProxyCookie.toSeq ++ maybeExtraCookie.toSeq :_*)
    UserPrincipal(
      firstName = bbcUser.firstName,
      lastName = bbcUser.lastName,
      email = bbcUser.email,
      attributes = attributes
    )
  }


  private def checkCookieValidity(cookie: String): PPSessionStatus = {
    Valid
  }

  private def checkExtraCookieValidity(cookie: String): PPSessionStatus = {
    Valid
  }

  private def checkRequestValidity(request: RequestHeader): PPSessionStatus = {
    val ppHeader = request.headers.get(idTokenHeaderKey)
    val ppCookie = request.cookies.get(ppProxyCookieName)
    val extraCookie = request.cookies.get(extraCookieName)

    if(ppHeader.isDefined) {
      return checkCookieValidity(ppHeader.get)
    } else if(ppCookie.isDefined) {
      return checkCookieValidity(ppCookie.get.value)
    }

    if(extraCookieEnabled && extraCookie.isDefined) {
      return checkExtraCookieValidity(extraCookie.get.value)
    }

    Invalid
  }

  private def getBBCUser(request: RequestHeader): Option[BBCBasicUserInfo] = {
    val email = request.headers.get(emailHeaderKey)
    if(email.isDefined) {
      return Some(BBCBasicUserInfo("John", "Doe", email.get))
    } else if(extraCookieEnabled) {
      val extraCookie =  request.cookies.get(extraCookieName)
      if(extraCookie.isDefined) {
        val decodeB64 = Base64.getDecoder().decode(extraCookie.get.value)
        val strCookie = new String(decodeB64, StandardCharsets.UTF_8)
        return Some(BBCBasicUserInfo("John", "Doe", strCookie))
      }
    }
    None
  }

  private def generateGridCookie(request: RequestHeader): Cookie = {
    val email = request.headers.get(emailHeaderKey).getOrElse("john.doe@bbc.co.uk")
    val base64mail = Base64.getEncoder.encodeToString(email.getBytes(StandardCharsets.UTF_8))

    Cookie(extraCookieName, base64mail, Some(maxAge), "/", Some(extraCookieDomain))
  }

  case class BBCBasicUserInfo(firstName: String, lastName: String, email: String)

  sealed trait PPSessionStatus
  case object Invalid extends PPSessionStatus
  case object Valid extends PPSessionStatus
}

object BBCPPProxyAuthenticationProvider {
  private val loginUri = "/login"
  private def decodePath(path: String): Option[String] = {
    Try(UriEncoding.decodePath(path, StandardCharsets.US_ASCII)).toOption
  }

  private def jsonParse(json: String): Option[JsValue] = {
    Try(Json.parse(json)).toOption
  }

  def getCKNSSessionOriginalURL(cookieContents: String): Option[String] = {
    for {
      decoded <- decodePath(cookieContents)
      json <- jsonParse(decoded)
      originalUrl <- (json \ "original_url").asOpt[String]
    } yield originalUrl
  }

  def CKNSSessionIsFromLogin(cookieContents: String): Boolean = {
    getCKNSSessionOriginalURL(cookieContents).exists(_.startsWith(loginUri))
  }
}
