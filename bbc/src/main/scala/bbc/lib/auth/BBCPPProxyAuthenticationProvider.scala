package bbc.lib.auth

import java.nio.charset.StandardCharsets
import java.util.Base64

import com.gu.mediaservice.lib.argo.ArgoHelpers
import com.gu.mediaservice.lib.auth.Authentication
import com.gu.mediaservice.lib.auth.Authentication.UserPrincipal
import com.gu.mediaservice.lib.auth.provider.AuthenticationProvider.RedirectUri
import com.gu.mediaservice.lib.auth.provider.{Authenticated, AuthenticationProviderResources, AuthenticationStatus, Expired, NotAuthenticated, UserAuthenticationProvider}
import com.typesafe.scalalogging.StrictLogging
import io.jsonwebtoken.impl.crypto.JwtSignatureValidator
import play.api.Configuration
import play.api.http.HeaderNames
import play.api.libs.typedmap.{TypedEntry, TypedKey, TypedMap}
import play.api.libs.ws.{DefaultWSCookie, WSClient, WSRequest}
import play.api.mvc.{ControllerComponents, Cookie, RequestHeader, Result}

import scala.concurrent.{ExecutionContext, Future}




class BBCPPProxyAuthenticationProvider (resources: AuthenticationProviderResources, providerConfiguration: Configuration)
  extends UserAuthenticationProvider with StrictLogging with ArgoHelpers with HeaderNames {

  implicit val ec: ExecutionContext = resources.controllerComponents.executionContext
  private val ppRedirectURI = providerConfiguration.getOptional[String]("app.loginURI").getOrElse(s"${resources.commonConfig.services.authBaseUri}/login")
  private val emailHeaderKey = providerConfiguration.getOptional[String]("pp.header.email").getOrElse("bbc-pp-oidc-id-token-email")
  private val idTokenHeaderKey = providerConfiguration.getOptional[String]("pp.header.idtoken").getOrElse("bbc-pp-oidc-id-token")
  private val expiryHeaderKey = providerConfiguration.getOptional[String]("pp.header.expiry").getOrElse("bbc-pp-oidc-id-token-expiry")
  private val ppProxyCookie = providerConfiguration.getOptional[String]("pp.cookie").getOrElse("ckns_pp_id")
  private val extraCookieHeaderKey = providerConfiguration.getOptional[String]("pp.extracookie.name").getOrElse("pp-grid-auth")
  private val extraCookieEnabled = providerConfiguration.getOptional[Boolean]("pp.extracookie.enabled").getOrElse(true)
  private val extraCookieDomain = providerConfiguration.getOptional[String]("pp.extracookie.domain").getOrElse(".images.int.tools.bbc.co.uk")
  private val defaultMaxAge = 60*20
  private val kahunaBaseURI: String = resources.commonConfig.services.kahunaBaseUri

  val ppCookieKey: TypedKey[Cookie] = TypedKey[Cookie](ppProxyCookie)
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
      case Authenticated(_) => Future(redirectToHome(requestHeader))
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
  override def sendForAuthenticationCallback: Option[(RequestHeader, Option[RedirectUri]) => Future[Result]] = None

  /**
    * If this provider is able to clear user tokens (i.e. by clearing cookies) then it should provide a function to
    * do that here which will be used to log users out and also if the token is invalid.
    * This function takes the request header and a result to modify and returns the modified result.
    */
  override def flushToken: Option[(RequestHeader, Result) => Result] = None

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
    val cookieName = ppProxyCookie
    request.attributes.get(ppCookieKey) match {
      case Some(cookie) => Right { wsRequest: WSRequest =>
        wsRequest.addCookies(DefaultWSCookie(cookieName, cookie.value))
      }
      case None => Left(s"Cookie $cookieName is missing in principal.")
    }
  }

  private def redirectToPP() = {
    Redirect(ppRedirectURI)
  }

  private def redirectToHome(request: RequestHeader) = {
    val redirect = Redirect(kahunaBaseURI)
    if(extraCookieEnabled) {
      redirect.withCookies(generateGridCookie(request))
    } else {
      redirect
    }
  }

  private def gridUserFrom(request: RequestHeader, bbcUser: BBCBasicUserInfo): UserPrincipal = {
    val maybePPProxyCookie: Option[TypedEntry[Cookie]] = request.cookies.get(ppProxyCookie).map(TypedEntry[Cookie](ppCookieKey, _))
    val attributes = TypedMap.empty + (maybePPProxyCookie.toSeq:_*)
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
    val ppCookie = request.cookies.get(ppProxyCookie)
    val extraCookie = request.cookies.get(extraCookieHeaderKey)

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
      val extraCookie =  request.cookies.get(extraCookieHeaderKey)
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

    Cookie(extraCookieHeaderKey, base64mail, Some(defaultMaxAge), "/", Some(extraCookieDomain))
  }

  case class BBCBasicUserInfo(firstName: String, lastName: String, email: String)

  sealed trait PPSessionStatus
  case object Invalid extends PPSessionStatus
  case object Valid extends PPSessionStatus
}

object BBCPPProxyAuthenticationProvider {

}
