package org.ibm.authz

import cats.effect.*
import cats.data.{Kleisli, OptionT}
import cats.implicits.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.Location
import org.http4s.server.AuthMiddleware
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import sttp.tapir.endpoint
import sttp.tapir.auth
import sttp.tapir.cookie
import org.ibm.TelCodec.jsonBody
import org.ibm.models.ApiError
import sttp.model.headers.WWWAuthenticateChallenge
case class AuthenticatedUser(
    userInfo: UserInfo,
    token: Option[String],
    introspectionResponse: Option[IntrospectionResponse]
)

object AuthenticatedUser {
  implicit val codec: JsonValueCodec[AuthenticatedUser] = JsonCodecMaker.make
}

object OIDCAuthMiddleware {
  val authenticatedEndpoint = endpoint
    .securityIn(
      auth
        .bearer[Option[String]]()
        .and(
          auth.apiKey(
            cookie[Option[String]](config.tokenName),
            WWWAuthenticateChallenge("ApiKey").realm(config.tokenName)
          )
        )
        .mapDecode { case (bearerToken, cookieToken) =>
          bearerToken.orElse(cookieToken) match {
            case Some(token) => sttp.tapir.DecodeResult.Value(token)
            case None =>
              sttp.tapir.DecodeResult.Missing // This won't create - {} with multiple optional auths
          }
        }(token => (Some(token), None))
    )
    .errorOut(jsonBody[ApiError])
    .serverSecurityLogic { token => // This connects Tapir to your existing validation logic
      tokenService.validateToken(token).map(Right(_))
    }

  lazy val config: OIDCConfig         = OIDCConfig.fromEnv
  lazy val tokenService: TokenService = new TokenService(config)
  private def getBearerToken(req: Request[IO]): Option[String] =
    req.headers
      .get[headers.Authorization]
      .collect {
        case headers.Authorization(
              Credentials.Token(AuthScheme.Bearer, token)
            ) =>
          token
      }
  private def extractToken(req: Request[IO]): Option[String] =
    getBearerToken(req).orElse(getCookie(req, config.tokenName).map(_.content))
  private def getCookie(req: Request[IO], name: String): Option[RequestCookie] =
    req.cookies.find(_.name == name)

  private def authUser: Kleisli[IO, Request[IO], Either[String, AuthenticatedUser]] =
    Kleisli { request =>
      extractToken(request) match {
        case Some(token) =>
          tokenService
            .validateToken(token)
            .map(x =>
              x match
                case Some(value) => Right(value)
                case None        => Left("Unable to authenticate user")
            )
        case None =>
          IO.pure(Left("No authentication token found"))
      }
    }
  private def isApiRequest(req: Request[IO]): Boolean = getBearerToken(req).isDefined

  private def onAuthFailure: AuthedRoutes[String, IO] =
    Kleisli { req =>
      // For API requests, return 401
      if (isApiRequest(req.req)) {
        OptionT.liftF(
          Unauthorized(
            headers.`WWW-Authenticate`(Challenge("Bearer", "api"))
          )
        )
      }
      else {
        // For browser requests, check if we can reuse existing token
        getCookie(req.req, config.tokenName) match {
          case Some(cookie) =>
            // Validate the existing token before redirecting
            OptionT.liftF(
              tokenService.validateToken(cookie.content).flatMap {
                case Some(userInfo) =>
                  // Token is still valid! Reuse it instead of redirecting
                  // This shouldn't happen if authUser worked, but defensive check
                  IO.pure(
                    Response[IO](status = Status.Found)
                      .withHeaders(Location(req.req.uri))
                  )
                case None =>
                  // Token invalid/expired - remove cookie and redirect to OAuth
                  val requestPath = req.req.uri.path.toString
                  val redirectUri = tokenService.authUri(requestPath)
                  TemporaryRedirect(Location(redirectUri)).map(
                    _.removeCookie(
                      ResponseCookie(
                        name = config.tokenName,
                        content = "",
                        maxAge = Some(-1)
                      )
                    )
                  )
              }
            )
          case None =>
            // No token - redirect to OAuth
            val requestPath = req.req.uri.path.toString
            val redirectUri = tokenService.authUri(requestPath)
            OptionT.liftF(TemporaryRedirect(Location(redirectUri)))
        }
      }
    }

  val middleware: AuthMiddleware[IO, AuthenticatedUser] =
    AuthMiddleware(authUser, onAuthFailure)

  // Helper to create protected routes
  def authenticated(
      pf: PartialFunction[AuthedRequest[IO, AuthenticatedUser], IO[
        Response[IO]
      ]]
  ): HttpRoutes[IO] = {
    val authedRoutes: AuthedRoutes[AuthenticatedUser, IO] = AuthedRoutes.of(pf)
    middleware(authedRoutes)
  }

  // Helper method to check if a user has a specific group/role
  def hasRole(user: AuthenticatedUser, role: String): Boolean =
    user.userInfo.groups.exists(_.contains(role))

  // Helper to create routes with role-based access control
  def authenticatedWithRole(role: String)(
      pf: PartialFunction[AuthedRequest[IO, AuthenticatedUser], IO[
        Response[IO]
      ]]
  ): HttpRoutes[IO] =
    authenticated {
      case req if hasRole(req.context, role) => pf(req)
      case _                                 => Forbidden("Insufficient permissions")
    }
}
