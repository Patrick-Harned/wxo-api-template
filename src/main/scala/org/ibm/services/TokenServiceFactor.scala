package org.ibm.services
import cats.effect.kernel.Async
import org.http4s.client.Client
import org.ibm.config.OIDCConfig

import cats.Apply.ops.toAllApplyOps
object TokenServiceFactory:
  // Expected env vars for Entra:
  // TOKEN_PROVIDER=entra
  // JWKS_URI=https://login.microsoftonline.com/{tenant}/discovery/v2.0/keys
  def make[F[_]: Async](
      client: Client[F],
      config: OIDCConfig
  ): F[TokenService[F]] =
    sys.env.get("TOKEN_PROVIDER").map(_.toLowerCase) match
      case Some("entra") =>
        val jwksUri = sys.env.getOrElse(
          "JWKS_URI",
          throw new RuntimeException("Missing JWKS_URI env var for Entra provider")
        )
        JwksService
          .live[F](client, jwksUri)
          .map(jwks => EntraTokenService.live[F](client, config, jwks))
      case _ =>
        // Default to W3ID - existing behaviour unchanged
        Async[F].pure(TokenService.live[F](client, config))
