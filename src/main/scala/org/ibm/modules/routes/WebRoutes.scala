package org.ibm.modules.routes

import cats.effect.kernel.Async
import org.http4s.HttpRoutes
import org.http4s.*
import org.http4s.dsl.io.*
import java.io.File
object WebRoutes:
  def routes[F[_]: Async]: HttpRoutes[F] =
    val notFound = Async[F].pure(Response.notFound[F])
    HttpRoutes.of[F] {
      case req @ GET -> Root if !req.uri.path.toString.startsWith("/docs") =>
        StaticFile
          .fromResource[F]("/index.html", Some(req))
          .getOrElseF(Async[F].pure(Response.notFound[F]))

      case req @ GET -> _
          if !req.uri.path.toString.startsWith("/docs") &&
            !req.uri.path.toString.startsWith("/api") && !req.uri.path.toString
              .startsWith("/callback") && !req.uri.path.toString.startsWith(
              "logout"
            ) =>
        val path: org.http4s.Uri.Path = req.uri.path
        val resourcePath              = if (path == Root) Root / "index.html" else path
        val pathStr                   = resourcePath.toString

        def looksLikeFile(p: String): Boolean = p.lastIndexOf('.') > p.lastIndexOf('/')

        val tryResource = sys.env.get("DEV") match {
          case Some(_) =>
            val file = new File("web/target/scala-3.7.1" + pathStr)
            StaticFile.fromFile(file, Some(req))
          case None =>
            StaticFile.fromResource(pathStr, Some(req))
        }

        if (looksLikeFile(pathStr)) {
          tryResource.getOrElseF(notFound)
        }
        else if (resourcePath.segments.size <= 1) {
          tryResource.getOrElseF(
            sys.env.get("DEV") match {
              case Some(_) =>
                StaticFile
                  .fromFile(
                    new File("web/target/scala-3.7.1/index.html"),
                    Some(req)
                  )
                  .getOrElseF(notFound)
              case None =>
                StaticFile
                  .fromResource("/index.html", Some(req))
                  .getOrElseF(notFound)
            }
          )
        }
        else {
          notFound
        }
    }
