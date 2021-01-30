package bbc.lib.auth

import com.gu.mediaservice.lib.argo.ArgoHelpers
import com.gu.mediaservice.lib.auth.Authentication
import com.gu.mediaservice.lib.auth.Authentication.UserPrincipal
import com.gu.mediaservice.lib.auth.provider.AuthenticationProvider.RedirectUri
import com.gu.mediaservice.lib.auth.provider.{Authenticated, AuthenticationProviderResources, AuthenticationStatus, Expired, UserAuthenticationProvider}
import com.typesafe.scalalogging.StrictLogging
import play.api.Configuration
import play.api.http.HeaderNames
import play.api.libs.ws.{WSClient, WSRequest}
import play.api.mvc.{ControllerComponents, RequestHeader, Result}

import scala.concurrent.{ExecutionContext, Future}

case class BBCPPProvidedInfo()

class BBCPPProxyAuthenticationProvider (resources: AuthenticationProviderResources, providerConfiguration: Configuration)
  extends UserAuthenticationProvider with StrictLogging with ArgoHelpers with HeaderNames {

  implicit val ec: ExecutionContext = resources.controllerComponents.executionContext

  private val ppRedirectURI = providerConfiguration.get[String]("app.loginURI")
  private val emailHeaderKey = providerConfiguration.getOptional[String]("pp.header.email").getOrElse("bbc-pp-oidc-id-token-email")
  private val idTokenHeaderKey = providerConfiguration.getOptional[String]("pp.header.idtoken").getOrElse("bbc-pp-oidc-id-token")
  private val expiryHeaderKey = providerConfiguration.getOptional[String]("pp.header.expiry").getOrElse("bbc-pp-oidc-id-token-expiry")
  private val extraCookieHeaderKey = providerConfiguration.getOptional[String]("pp.extracookie.name").getOrElse("pp-grid-auth")
  private val extraCookieEnabled = providerConfiguration.getOptional[Boolean]("pp.extracookie.enabled").getOrElse(true)


  /**
    * Establish the authentication status of the given request header. This can return an authenticated user or a number
    * of reasons why a user is not authenticated.
    *
    * @param request The request header containing cookies and other request headers that can be used to establish the
    *                authentication status of a request.
    * @return An authentication status expressing whether the
    */
  override def authenticateRequest(request: RequestHeader): AuthenticationStatus = ???

  /**
    * If this provider supports sending a user that is not authorised to a federated auth provider then it should
    * provide a function here to redirect the user. The function signature takes the the request and returns a result
    * which is likely a redirect to an external authentication system.
    */
  override def sendForAuthentication: Option[RequestHeader => Future[Result]] = Some({requestHeader: RequestHeader =>
    authenticateRequest(requestHeader) match {
      case Authenticated(_) =>
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
  override def sendForAuthenticationCallback: Option[(RequestHeader, Option[RedirectUri]) => Future[Result]] = ???

  /**
    * If this provider is able to clear user tokens (i.e. by clearing cookies) then it should provide a function to
    * do that here which will be used to log users out and also if the token is invalid.
    * This function takes the request header and a result to modify and returns the modified result.
    */
  override def flushToken: Option[(RequestHeader, Result) => Result] = ???

  /**
    * A function that allows downstream API calls to be made using the credentials of the current principal.
    * It is recommended that any data required for this downstream request enrichment is put into the principal's
    * attribute map when the principal is created in the authenticateRequest call.
    *
    * @param principal The principal for the current request
    * @return Either a function that adds appropriate authentication headers to a WSRequest or an error string explaining
    *         why it wasn't possible to create a function.
    */
  override def onBehalfOf(principal: Authentication.Principal): Either[String, WSRequest => WSRequest] = ???

  private def redirectToPP() = {
    Redirect(ppRedirectURI)
  }
}
