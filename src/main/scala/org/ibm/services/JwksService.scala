package org.ibm.services
import cats.effect.kernel.Async
import cats.effect.Ref
import cats.implicits.*
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import org.http4s.client.Client
import org.http4s.Uri
import java.security.PublicKey
trait JwksService[F[_]]:
  def getPublicKey(kid: String): F[Option[PublicKey]]
object JwksService:
  def live[F[_]: Async](
      client: Client[F],
      jwksUri: String
  ): F[JwksService[F]] =
    // Ref holds our cache: Map of kid -> PublicKey
    Ref.of[F, Map[String, PublicKey]](Map.empty).map { cache =>
      new JwksService[F]:
        def getPublicKey(kid: String): F[Option[PublicKey]] =
          cache.get.flatMap { currentCache =>
            currentCache.get(kid) match
              // Cache hit
              case Some(key) =>
                Async[F].pure(Some(key))
              // Cache miss - fetch fresh keys from Microsoft
              case None =>
                fetchAndCacheKeys().map(_.get(kid))
          }
        private def fetchAndCacheKeys(): F[Map[String, PublicKey]] =
          client
            .expect[String](Uri.unsafeFromString(jwksUri))
            .flatMap { body =>
              Async[F].delay {
                // Nimbus parses the JWKS JSON for us
                val jwkSet = JWKSet.parse(body)
                val keys = jwkSet
                  .getKeys
                  .toArray
                  .collect { case rsa: RSAKey => rsa }
                  .map { rsa =>
                    rsa.getKeyID -> rsa.toPublicKey.asInstanceOf[PublicKey]
                  }
                  .toMap
                keys
              }
            }
            .flatTap { keys =>
              // Update the cache with newly fetched keys
              cache.update(_ ++ keys)
            }
            .handleErrorWith { err =>
              Async[F].delay(
                println(s"[JwksService] Failed to fetch JWKS keys: ${err.getMessage}")
              ) *> Async[F].pure(Map.empty)
            }
    }
