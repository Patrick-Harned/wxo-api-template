package org.ibm

import cats.effect.*
import cats.implicits.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.server.middleware.{Logger, CORS, CORSConfig}
import sttp.tapir.server.http4s.Http4sServerInterpreter
import org.ibm.authz.*
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import org.http4s.StaticFile
import java.io.File
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import scala.concurrent.duration._
import org.ibm.authz.UserInfo.{given, _}
import org.ibm.tls.TLSSupport
import org.ibm.tls.TLSConfig
import sttp.tapir.server.http4s.Http4sServerOptions
import sttp.tapir.server.interceptor.log.DefaultServerLog
import sttp.tapir.server.interceptor.RequestInterceptor
import sttp.tapir.server.ServerEndpoint
import org.ibm.TelCodec.jsonBody

object EPMTool extends IOApp {

  val config: OIDCConfig = OIDCConfig.fromEnv
  val tokenService       = new TokenService(config)
  val callbackRoutes     = new OAuthCallbackRoutes(config, tokenService)

  // Public routes (no authentication required)
  val publicRoutes = HttpRoutes.of[IO] { case GET -> Root / "health" / "ping" =>
    Ok("Ok")
  } <+> echoTokenRoute

  val swaggerRoutes =
    Http4sServerInterpreter[IO]()
      .toRoutes(SwaggerDocumentation.swaggerEndpoints)

  val apiRoutes: HttpRoutes[IO] = {

    val rawApiRoutes = Http4sServerInterpreter[IO](serverOptions)
      .toRoutes(SwaggerDocumentation.allEndpoints)
    rawApiRoutes
  }
  // Web routes for SPA
  val webRoutes = HttpRoutes.of[IO] {
    case req @ GET -> Root if !req.uri.path.toString.startsWith("/docs") =>
      StaticFile.fromResource("/index.html", Some(req)).getOrElseF(NotFound())

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
        tryResource.getOrElseF(NotFound())
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
                .getOrElseF(NotFound(pathStr))
            case None =>
              StaticFile
                .fromResource("/index.html", Some(req))
                .getOrElseF(NotFound(pathStr))
          }
        )
      }
      else {
        NotFound()
      }
  }
  val secureWebRoutes = OIDCAuthMiddleware.middleware(AuthedRoutes { case req =>
    webRoutes.run(req.req)
  })
  def run(args: List[String]): IO[ExitCode] = {
    val tlsConfig: TLSConfig = TLSConfig.fromEnv
    val port                 = sys.env.getOrElse("SERVER_PORT", "8080").toInt
    val routes =
      publicRoutes <+> OIDCAuthMiddleware.middleware(AuthedRoutes { case req =>
        swaggerRoutes.run(req.req)
      })
    val allRoutes =
      MCPRoutes.mcpRoutes <+> callbackRoutes.routes <+> routes <+> apiRoutes
    val corsRoutes = CORS.policy
      .withAllowOriginHostCi(_ => true) // Or specify exact origins
      .withAllowCredentials(true)       // ✅ Allow cookies
      .withAllowMethodsAll
      .withAllowHeadersAll
      .apply(allRoutes)

    // Add request/response logging
    val httpApp = Logger.httpApp(
      logHeaders = sys.env.get("DEV").isDefined,
      logBody = sys.env.get("DEV").isDefined
    )(corsRoutes.orNotFound)

    for {
      // Ensure certificate exists if TLS is enabled
      _ <- IO(TLSSupport.certificateExists(tlsConfig.keystorePath))

      // Write OpenAPI docs
      _ <- IO {
        val docs = SwaggerDocumentation.docsAsJson
        Files.write(
          Paths.get("openapi.yaml"),
          docs.getBytes(StandardCharsets.UTF_8)
        )
        println("OpenAPI documentation written to openapi.yaml")
      }

      // Log startup info
      _ <- IO.println("=" * 60)
      _ <- IO.println(s"Starting TelAssets API Server")
      _ <- IO.println("=" * 60)
      _ <-
        if (tlsConfig.enabled) {
          IO.println(s"✓ TLS/SSL: ENABLED") *>
            IO.println(s"  Keystore: ${tlsConfig.keystorePath}") *>
            IO.println(s"  Server: https://localhost:$port")
        }
        else {
          IO.println(s"⚠ TLS/SSL: DISABLED (HTTP only)") *>
            IO.println(s"  Server: http://localhost:$port")
        }
      _ <- IO.println(s"✓ OIDC Authentication: ENABLED")
      _ <- IO.println(s"  Callback URL: ${config.redirectUri}")
      _ <- IO.println(s"  Client ID: ${config.clientId}")
      _ <- IO.println("=" * 60)

      // Create and configure server builder
      serverBuilder = BlazeServerBuilder[IO]
        .bindHttp(port, "0.0.0.0")
        .withHttpApp(httpApp)
        .withIdleTimeout(120.seconds)
        .withResponseHeaderTimeout(60.seconds)
      // search <- Routes.initializeSearchService()
      _ <- {
        if (tlsConfig.enabled) {
          BlazeServerBuilder[IO]
            .bindHttp(8443, "0.0.0.0")
            .withHttpApp(httpApp)
            .withIdleTimeout(120.seconds)
            .withResponseHeaderTimeout(60.seconds)
            .withSslContext(TLSSupport.loadSSLContext(tlsConfig))
            .resource
            .use(_ => IO.never)
            .as(ExitCode.Success)
        }
        else {
          serverBuilder.resource
            .use(_ => IO.never)
            .as(ExitCode.Success)

        }
      }
    } yield ExitCode.Success
  }
}
