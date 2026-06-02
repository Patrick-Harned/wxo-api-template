package org.ibm.modules.routes

import cats.effect.kernel.Async
import org.http4s.HttpRoutes
import org.http4s.*
import org.http4s.dsl.io.*
import java.io.File
import cats.data.OptionT
import org.http4s.headers.`Content-Type`
import org.ibm.domain.AuthenticatedUser
import org.ibm.services.WxoJwtService
import org.ibm.config.WXOConfig
import html._
import cats.Applicative.ops.toAllApplicativeOps
object WebRoutes:
  def routes[F[_]: Async](
      jwtService: WxoJwtService[F],
      wxoConfig: WXOConfig
  ): AuthedRoutes[AuthenticatedUser, F] =
    val notFound = Async[F].pure(Response.notFound[F])
    AuthedRoutes {
      case req @ (GET -> Root) as user =>
        OptionT.liftF(
          jwtService.createJwtToken(user).map { ssoToken =>
            Response[F](Status.Ok)
              .withEntity(
                html
                  .index(
                    ssoToken.toString(),
                    wxoConfig.orchestrationId,
                    wxoConfig.hostUrl,
                    wxoConfig.agentId,
                    wxoConfig.agentEnvironmentId,
                    user.userInfo.name.getOrElse(user.userInfo.sub),
                    user.userInfo.sub
                  )
                  .body
              )
              .withContentType(`Content-Type`(MediaType.text.html))
          }
        )
      case req @ (GET -> _) as user
          if !req.req.uri.path.toString.startsWith("/docs") &&
            !req.req.uri.path.toString.startsWith("/api") &&
            !req.req.uri.path.toString.startsWith("/callback") &&
            !req.req.uri.path.toString.startsWith("/logout") =>
        val path                              = req.req.uri.path
        val pathStr                           = path.toString
        def looksLikeFile(p: String): Boolean = p.lastIndexOf('.') > p.lastIndexOf('/')
        val tryResource = sys.env.get("DEV") match {
          case Some(_) =>
            StaticFile.fromFile(new File("web/target/scala-3.7.1" + pathStr), Some(req.req))
          case None =>
            StaticFile.fromResource(pathStr, Some(req.req))
        }
        if (looksLikeFile(pathStr))
          OptionT.liftF(tryResource.getOrElseF(notFound))
        else
          OptionT.liftF(
            tryResource.getOrElseF(
              sys.env.get("DEV") match {
                case Some(_) =>
                  StaticFile
                    .fromFile(new File("web/target/scala-3.7.1/index.html"), Some(req.req))
                    .getOrElseF(notFound)
                case None =>
                  StaticFile.fromResource("/index.html", Some(req.req)).getOrElseF(notFound)
              }
            )
          )
    }
