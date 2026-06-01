package org.ibm.modules.routes
import cats.data.{Kleisli, OptionT}
import cats.effect.kernel.Async
import cats.implicits.*
import org.http4s.*
import org.http4s.dsl.*
import org.http4s.headers.Location
import org.http4s.server.AuthMiddleware
import org.ibm.config.OIDCConfig
import org.ibm.domain.AuthenticatedUser
import org.ibm.services.TokenService
trait AuthenticationMiddleware[F[_]]:
  def middleware(routes: AuthedRoutes[AuthenticatedUser, F]): HttpRoutes[F]
  def hasRole(user: AuthenticatedUser, role: String): Boolean
  def authenticatedWithRole(role: String)(
      routes: AuthedRoutes[AuthenticatedUser, F]
  ): HttpRoutes[F]
object AuthenticationMiddleware:
  def live[F[_]: Async](
      tokenService: TokenService[F],
      config: OIDCConfig
  ): AuthenticationMiddleware[F] =
    new AuthenticationMiddleware[F]:
      private val dsl = new Http4sDsl[F] {}
      import dsl._
      private def getBearerToken(req: Request[F]): Option[String] =
        req.headers
          .get[headers.Authorization]
          .collect {
            case headers.Authorization(
                  Credentials.Token(AuthScheme.Bearer, token)
                ) =>
              token
          }
      private def getCookie(req: Request[F], name: String): Option[RequestCookie] =
        req.cookies.find(_.name == name)
      private def extractToken(req: Request[F]): Option[String] =
        getBearerToken(req).orElse(getCookie(req, config.tokenName).map(_.content))
      private def isApiRequest(req: Request[F]): Boolean = getBearerToken(req).isDefined
      private val authUser: Kleisli[F, Request[F], Either[String, AuthenticatedUser]] =
        Kleisli { request =>
          extractToken(request) match {
            case Some(token) =>
              tokenService.validateToken(token).map {
                case Some(user) => Right(user)
                case None       => Left("Unable to authenticate user")
              }
            case None =>
              Async[F].pure(Left("No authentication token found"))
          }
        }
      private val onAuthFailure: AuthedRoutes[String, F] =
        Kleisli { req =>
          if (isApiRequest(req.req)) {
            OptionT.liftF(
              Unauthorized(headers.`WWW-Authenticate`(Challenge("Bearer", "api")))
            )
          }
          else {
            getCookie(req.req, config.tokenName) match {
              case Some(cookie) =>
                OptionT.liftF(
                  tokenService.validateToken(cookie.content).flatMap {
                    case Some(_) =>
                      Async[F].pure(
                        Response[F](status = Status.Found)
                          .withHeaders(Location(req.req.uri))
                      )
                    case None =>
                      val requestPath = req.req.uri.path.toString
                      TemporaryRedirect(Location(tokenService.authUri(requestPath))).map(
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
                OptionT.liftF(
                  TemporaryRedirect(Location(tokenService.authUri(req.req.uri.path.toString)))
                )
            }
          }
        }
      private val underlying: AuthMiddleware[F, AuthenticatedUser] =
        org.http4s.server.AuthMiddleware(authUser, onAuthFailure)
      def middleware(routes: AuthedRoutes[AuthenticatedUser, F]): HttpRoutes[F] = underlying(routes)
      def hasRole(user: AuthenticatedUser, role: String): Boolean =
        user.userInfo.groups.exists(_.contains(role))
      def authenticatedWithRole(role: String)(
          routes: AuthedRoutes[AuthenticatedUser, F]
      ): HttpRoutes[F] =
        middleware(
          AuthedRoutes { req =>
            if (hasRole(req.context, role)) routes.run(req)
            else OptionT.liftF(Forbidden("Insufficient permissions"))
          }
        )
