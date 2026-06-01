package org.ibm
import cats.effect.*
import cats.implicits.*
import org.http4s.*
import org.http4s.server.middleware.{Logger, CORS}
import org.http4s.blaze.client.BlazeClientBuilder
import sttp.tapir.server.http4s.Http4sServerInterpreter
import scala.concurrent.duration.*
import org.ibm.config.{OIDCConfig, TLSConfig}
import org.ibm.modules.routes._
import org.ibm.services._
import org.ibm.config.AppConfig
object Main extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    val appConfig  = AppConfig.fromEnv
    val oidcConfig = appConfig.oidcConfig
    val jwtConfig  = appConfig.jwtConfig
    val tlsConfig  = appConfig.tlsConfig
    val port       = sys.env.getOrElse("SERVER_PORT", "8080").toInt
    val program: Resource[IO, Unit] = for {
      // Infrastructure
      client <- BlazeClientBuilder[IO].resource
      // Services

      tokenService <- TokenServiceFactory.make[IO](client, oidcConfig).toResource
      jwtService = WxoJwtService.live[IO](jwtConfig, tokenService)
      // Public routes
      publicRoutes = PublicRoutes.routes[IO]
      authRoutes   = AuthRoutes.live[IO](tokenService, oidcConfig).routes
      echoRoutes   = EchoRoutes.live[IO](tokenService, oidcConfig).routes
      // Protected routes
      webRoutes = WebRoutes.routes[IO]

      protectedRoutes = AuthenticationMiddleware.live(tokenService, oidcConfig).middleware {
        AuthedRoutes { case req =>
          webRoutes.run(req.req)
        }
      }
      // Combine
      allRoutes = publicRoutes <+> authRoutes <+> echoRoutes <+> protectedRoutes
      corsRoutes = CORS.policy
        .withAllowOriginHostCi(_ => true)
        .withAllowCredentials(true)
        .withAllowMethodsAll
        .withAllowHeadersAll
        .apply(allRoutes)
      httpApp = Logger.httpApp(
        logHeaders = sys.env.get("DEV").isDefined,
        logBody = sys.env.get("DEV").isDefined
      )(corsRoutes.orNotFound)
      // Start server
      _ <- Server.start[IO](httpApp, tlsConfig, port)
    } yield ()
    for {
      _ <- IO.println("=" * 60)
      _ <- IO.println("Starting TelAssets API Server")
      _ <- IO.println("=" * 60)
      _ <-
        if (tlsConfig.enabled)
          IO.println(s"✓ TLS/SSL: ENABLED") *>
            IO.println(s"  Keystore: ${tlsConfig.keystorePath}") *>
            IO.println(s"  Server: https://localhost:$port")
        else
          IO.println(s"⚠ TLS/SSL: DISABLED (HTTP only)") *>
            IO.println(s"  Server: http://localhost:$port")
      _ <- IO.println(s"✓ OIDC Authentication: ENABLED")
      _ <- IO.println(s"  Callback URL: ${oidcConfig.redirectUri}")
      _ <- IO.println(s"  Client ID: ${oidcConfig.clientId}")
      _ <- IO.println("=" * 60)
      _ <- program.useForever
    } yield ExitCode.Success
  }
}
