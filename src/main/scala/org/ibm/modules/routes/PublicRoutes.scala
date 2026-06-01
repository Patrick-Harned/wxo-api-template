package org.ibm.modules.routes
import cats.effect._
import org.http4s.*
import org.http4s.dsl.Http4sDsl

object PublicRoutes:
  def routes[F[_]: Async]: HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl._
    HttpRoutes.of[F] { case GET -> Root / "health" / "ping" =>
      Ok("Ok")
    }
