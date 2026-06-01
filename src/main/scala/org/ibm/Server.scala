package org.ibm
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import org.http4s.HttpApp
import org.http4s.blaze.server.BlazeServerBuilder
import scala.concurrent.duration.*
import org.ibm.config.TLSConfig
import org.ibm.tls.TLSSupport
import cats.Applicative.ops.toAllApplicativeOps
object Server:
  def start[F[_]: Async](
      httpApp: HttpApp[F],
      tlsConfig: TLSConfig,
      port: Int
  ): Resource[F, Unit] =
    if (tlsConfig.enabled)
      BlazeServerBuilder[F]
        .bindHttp(8443, "0.0.0.0")
        .withHttpApp(httpApp)
        .withIdleTimeout(120.seconds)
        .withResponseHeaderTimeout(60.seconds)
        .withSslContext(TLSSupport.loadSSLContext(tlsConfig))
        .resource
        .void
    else
      BlazeServerBuilder[F]
        .bindHttp(port, "0.0.0.0")
        .withHttpApp(httpApp)
        .withIdleTimeout(120.seconds)
        .withResponseHeaderTimeout(60.seconds)
        .resource
        .void
