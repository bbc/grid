package bbc.lib.auth

import bbc.lib.auth.BBCPPProxyAuthenticationProvider.jsonParse
import bbc.lib.auth.Crypto.{PrivateKey, PublicKey}
import play.api.libs.json.{JsObject, Reads}

import java.nio.charset.StandardCharsets
import scala.util.Success
//import java.util.Base64
import bbc.lib.auth.BBCPPProxyAuthenticationProvider.CKNSSessionIsFromLogin
import com.gu.mediaservice.lib.argo.ArgoHelpers
import com.gu.mediaservice.lib.auth.Authentication
import com.gu.mediaservice.lib.auth.Authentication.UserPrincipal
import com.gu.mediaservice.lib.auth.provider.AuthenticationProvider.RedirectUri
import com.gu.mediaservice.lib.auth.provider.{Authenticated, AuthenticationProviderResources, AuthenticationStatus, Expired, NotAuthenticated, UserAuthenticationProvider}
import com.typesafe.scalalogging.StrictLogging
import io.jsonwebtoken.{JwtParser, Jwts}
import io.jsonwebtoken.impl.crypto.JwtSignatureValidator
import play.api.Configuration
import play.api.http.HeaderNames
import play.api.libs.json._
import play.api.libs.typedmap.{TypedEntry, TypedKey, TypedMap}
import play.api.libs.ws.{DefaultWSCookie, WSClient, WSRequest}
import play.api.mvc.{ControllerComponents, Cookie, DiscardingCookie, RequestHeader, Result}
import play.utils.{InvalidUriEncodingException, UriEncoding}
import play.api.libs.functional.syntax._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import org.apache.commons.codec.binary.Base64

sealed case class UserGroup(name: String, id: String)
object UserGroup {
  implicit val userGroupReads: Reads[UserGroup] = (
    (JsPath \ "name").read[String] and
      (JsPath \ "id").read[String]
    )(UserGroup.apply _)
}

object BBCImages extends UserGroup("BBC%20Images", "690e9d9d-3de8-4542-87c7-b643801c0575")
object BBCImagesArchivist extends UserGroup("BBC_Images_Archivist", "5c37e500-946a-4c5b-a38a-12498f176ead")



class BBCPPProxyAuthenticationProvider (resources: AuthenticationProviderResources, providerConfiguration: Configuration)
  extends UserAuthenticationProvider with StrictLogging with ArgoHelpers with HeaderNames {

  implicit val ec: ExecutionContext = resources.controllerComponents.executionContext
  private val defaultMaxAge = 60*60*4
  private val ppRedirectURI = providerConfiguration.getOptional[String]("app.loginURI").getOrElse(s"${resources.commonConfig.services.authBaseUri}/login")
  private val ppRedirectLogoutURI = providerConfiguration.getOptional[String]("app.logoutURI").getOrElse(s"${resources.commonConfig.services.authBaseUri}/logout")
  private val emailHeaderKey = providerConfiguration.getOptional[String]("pp.header.email").getOrElse("bbc-pp-oidc-id-token-email")
  private val idTokenHeaderKey = providerConfiguration.getOptional[String]("pp.header.idtoken").getOrElse("bbc-pp-oidc-id-token")
  private val expiryHeaderKey = providerConfiguration.getOptional[String]("pp.header.expiry").getOrElse("bbc-pp-oidc-id-token-expiry")
  private val userGroupsHeaderKey = providerConfiguration.getOptional[String]("pp.header.usergroups").getOrElse("bbc-pp-user-groups")
  private val ppProxyCookieName = providerConfiguration.getOptional[String]("pp.cookie").getOrElse("ckns_pp_id")
  private val ppProxySessionCookie = providerConfiguration.getOptional[String]("pp.session_cookie").getOrElse("ckns_pp_session")
  private val extraCookieName = providerConfiguration.getOptional[String]("pp.extracookie.name").getOrElse("pp-grid-auth")
  private val extraCookieDomain = providerConfiguration.getOptional[String]("pp.extracookie.domain").getOrElse(".images.int.tools.bbc.co.uk")
  private val maxAge = providerConfiguration.getOptional[Int]("pp.extracookie.maxage").getOrElse(defaultMaxAge)
  private val kahunaBaseURI: String = resources.commonConfig.services.kahunaBaseUri
  private val privateKey: PrivateKey = PrivateKey(providerConfiguration.get[String]("pp.extracookie.privateKey"))
  private val publicKey: PublicKey = PublicKey(providerConfiguration.get[String]("pp.extracookie.publicKey"))

  private val ppProxyCookieKey: TypedKey[Cookie] = TypedKey[Cookie](ppProxyCookieName)
  private val extraCookieKey: TypedKey[Cookie] = TypedKey[Cookie](extraCookieName)
  private val userGroupKey: TypedKey[List[UserGroup]] = TypedKey[List[UserGroup]]("userGroups")

  /**
    * Establish the authentication status of the given request header. This can return an authenticated user or a number
    * of reasons why a user is not authenticated.
    *
    * @param request The request header containing cookies and other request headers that can be used to establish the
    *                authentication status of a request.
    * @return An authentication status expressing whether the
    */
  override def authenticateRequest(request: RequestHeader): AuthenticationStatus = {
    getBBCUser(request).map(bbcUser => Authenticated(gridUserFrom(request, bbcUser))).getOrElse(NotAuthenticated)
  }

  /**
    * If this provider supports sending a user that is not authorised to a federated auth provider then it should
    * provide a function here to redirect the user. The function signature takes the the request and returns a result
    * which is likely a redirect to an external authentication system.
    *
    * PP Proxy specific: Assumes that the user is authenticated since it got through PP Proxy, creates auth cookie.
    */
  override def sendForAuthentication: Option[RequestHeader => Future[Result]] = Some({requestHeader: RequestHeader =>
    Future(redirectToSource(requestHeader))
  })

  /**
    * If this provider supports sending a user that is not authorised to a federated auth provider then it should
    * provide a function here that deals with the return of a user from a federated provider. This should be
    * used to set a cookie or similar to ensure that a subsequent call to authenticateRequest will succeed. If
    * authentication failed then this should return an appropriate 4xx result.
    * The function should take the Play request header and the redirect URI that the user should be
    * sent to on successful completion of the authentication.
    */
  override def sendForAuthenticationCallback: Option[(RequestHeader, Option[RedirectUri]) => Future[Result]] =
    Some {(requestHeader: RequestHeader, _: Option[RedirectUri]) => {
      val ppSessionCookie = requestHeader.cookies.get(ppProxySessionCookie)
      val isCKNSSessionFromLogin = ppSessionCookie.exists(cookie => CKNSSessionIsFromLogin(cookie.value))
      if(isCKNSSessionFromLogin) {
        Future(redirectToPP())
      } else Future(Redirect(kahunaBaseURI))
    }
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
    request.attributes.get(extraCookieKey) match {
      case Some(cookie) => Right { wsRequest: WSRequest =>
        wsRequest.addCookies(DefaultWSCookie(cookie.name, cookie.value))
      }
      case None => Left(s"Cookie $extraCookieName is missing in principal.")
    }
  }

  private def redirectToPP() = {
    Redirect(ppRedirectURI)
  }

  private def redirectToSource(request: RequestHeader) = {
    val redirect = request.getQueryString("redirectUri").map(redirectURL => Redirect(redirectURL)).getOrElse(Redirect(kahunaBaseURI))
    redirect.withCookies(generateGridCookie(request))
  }

  private def gridUserFrom(request: RequestHeader, bbcUser: BBCBasicUserInfo): UserPrincipal = {
    val maybePPProxyCookie: Option[TypedEntry[Cookie]] = request.cookies.get(ppProxyCookieName).map(TypedEntry[Cookie](ppProxyCookieKey, _))
    val maybeExtraCookie: Option[TypedEntry[Cookie]] = request.cookies.get(extraCookieName).map(TypedEntry[Cookie](extraCookieKey, _))
    val userGroups: TypedEntry[List[UserGroup]] = TypedEntry[List[UserGroup]](userGroupKey, bbcUser.userGroups)
    val attributes = TypedMap.empty + (maybePPProxyCookie.toSeq ++ maybeExtraCookie.toSeq :_*) + userGroups
    UserPrincipal(
      firstName = bbcUser.firstName,
      lastName = bbcUser.lastName,
      email = bbcUser.email,
      attributes = attributes
    )
  }

  private def getBBCUser(request: RequestHeader): Option[BBCBasicUserInfo] = for {
    extraCookie <- request.cookies.get(extraCookieName)
    decodedExtraCookieData <- BBCPPProxyAuthenticationProvider.decodeCookieData(extraCookie.value, publicKey)
    jsonString <- jsonParse(decodedExtraCookieData)
    jsObj <- jsonString.asOpt[JsObject]
    email <- (jsObj \ "email").asOpt[String]
    userGroups <- (jsObj \ "userGroups").asOpt[List[UserGroup]]
  } yield {
    BBCBasicUserInfo("John", "Doe", email, userGroups)
  }

  private def generateGridCookie(request: RequestHeader): Cookie = {
    val email = request.headers.get(emailHeaderKey)
    val userGroups = request.headers.get(userGroupsHeaderKey)
    logger.info("userGroups get: ", userGroups)
    val data = Json.obj("email" -> email, "userGroups" -> userGroups).toString
    val encodedData = Base64.encodeBase64String(data.getBytes(StandardCharsets.UTF_8))
    val signature = Crypto.signData(data.getBytes("UTF-8"), privateKey)
    val encodedSignature = Base64.encodeBase64String(signature)

    Cookie(extraCookieName, s"$encodedData.$encodedSignature", Some(maxAge), "/", Some(extraCookieDomain))
  }

  case class BBCBasicUserInfo(firstName: String, lastName: String, email: String, userGroups: List[UserGroup])

  sealed trait PPSessionStatus
  case object Invalid extends PPSessionStatus
  case object Valid extends PPSessionStatus
}

object BBCPPProxyAuthenticationProvider {
  private val loginUri = "/login"
  private lazy val CookieRegEx = "^^([\\w\\W]*)\\.([\\w\\W]*)$".r
  private def decodePath(path: String): Option[String] = {
    Try(UriEncoding.decodePath(path, StandardCharsets.US_ASCII)).toOption
  }

  def decodeCookieData(cookieString: String, publicKey: PublicKey): Option[String] = cookieString match {
    case CookieRegEx(data, sig) =>
      val decodedData = Base64.decodeBase64(data.getBytes("UTF-8"))
      val decodedSignature = Base64.decodeBase64(sig.getBytes("UTF-8"))
      Try(Crypto.verifySignature(decodedData, decodedSignature, publicKey)) match {
        case Success(true) => Some(new String(decodedData))
        case _ => None
      }
    case _ => None
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
