package org.ibm.modules.routes

import org.http4s.dsl.io.*
import cats.effect.kernel.Async
import org.http4s.HttpRoutes
import org.http4s._
import org.http4s.dsl.Http4sDsl
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
          jwtService.createJwtToken(user).map {
            case Right(ssoToken) =>
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
            case Left(errorMessage) =>
              Response[F](Status.Ok)
                .withEntity(serviceErrorPage(errorMessage))
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
  private def serviceErrorPage(errorMessage: String): String =
    s"""
    <!DOCTYPE html>
    <html lang="en">
    <head>
      <meta charset="UTF-8">
      <meta name="viewport" content="width=device-width, initial-scale=1.0">
      <title>Service Error</title>
      <style>
        body {
          font-family: 'IBM Plex Sans', Arial, sans-serif;
          display: flex;
          justify-content: center;
          align-items: center;
          height: 100vh;
          margin: 0;
          background-color: #f4f4f4;
        }
        .error-card {
          background: white;
          padding: 2rem 3rem;
          border-radius: 4px;
          border-left: 4px solid #da1e28;
          max-width: 480px;
          box-shadow: 0 2px 6px rgba(0,0,0,0.1);
        }
        h2 { color: #da1e28; margin-top: 0; }
        p  { color: #525252; line-height: 1.5; }
        a  {
          display: inline-block;
          margin-top: 1rem;
          padding: 0.75rem 1.5rem;
          background: #0f62fe;
          color: white;
          text-decoration: none;
          border-radius: 4px;
        }
        a:hover { background: #0353e9; }
      </style>
    </head>
    <body>
      <div class="error-card">
        <h2>Session Error</h2>
        <p>${escapeHtml(errorMessage)}</p>
        <a href="/logout">Sign out and try again</a>
      </div>
    </body>
    </html>
    """
  private def escapeHtml(s: String): String =
    s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#x27;")
