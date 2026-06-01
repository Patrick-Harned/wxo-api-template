package org.ibm.services
import cats.effect.kernel.Async
import cats.implicits.*
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import org.http4s.client.Client
import org.http4s.Uri
import org.http4s.*
import org.http4s.headers.*
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.dsl.io.*
import java.net.URLEncoder
import java.util.Base64
import com.github.plokhotnyuk.jsoniter_scala.core.*
import org.ibm.config.OIDCConfig
import org.ibm.domain.*
object EntraTokenService:
  def live[F[_]: Async](
      client: Client[F],
      config: OIDCConfig,
      jwksService: JwksService[F]
  ): TokenService[F] =
    new TokenService[F] with Http4sClientDsl[F] with JsoniterInstances:
      // Validates the token locally using JWKS instead of introspection
      def validateToken(token: String): F[Option[AuthenticatedUser]] =
        verifyJwt(token).map { x =>
          // Build IntrospectionResponse from JWT claims
          // so the rest of the app works identically
          x.map { claims =>
            val userInfo = UserInfo(
              sub = claims.getSubject,
              name = Option(claims.getStringClaim("name")),
              email = Option(claims.getStringClaim("email"))
                .orElse(Option(claims.getStringClaim("upn"))),
              preferred_username = Option(claims.getStringClaim("preferred_username")),
              groups = None
            )
            val introspection = IntrospectionResponse(
              active = true,
              scope = Option(claims.getStringClaim("scp"))
                .orElse(Option(claims.getStringClaim("scope"))),
              client_id = Option(claims.getStringClaim("appid"))
                .orElse(Option(claims.getStringClaim("azp"))),
              username = Option(claims.getStringClaim("preferred_username"))
                .orElse(Option(claims.getStringClaim("upn"))),
              exp = Option(claims.getExpirationTime)
                .map(_.getTime / 1000)
            )
            AuthenticatedUser(userInfo, token, Some(introspection))
          }
        }
      // Local JWT signature verification using cached JWKS keys
      private def verifyJwt(
          token: String
      ): F[Option[com.nimbusds.jwt.JWTClaimsSet]] =
        Async[F]
          .delay(SignedJWT.parse(token))
          .flatMap { jwt =>
            val kid = jwt.getHeader.getKeyID
            jwksService.getPublicKey(kid).flatMap {
              case None =>
                Async[F].delay(
                  println(s"[EntraTokenService] No key found for kid: $kid")
                ) *> Async[F].pure(None)
              case Some(publicKey) =>
                Async[F].delay {
                  val verifier = new RSASSAVerifier(
                    publicKey.asInstanceOf[java.security.interfaces.RSAPublicKey]
                  )
                  val isValid = jwt.verify(verifier)
                  val claims  = jwt.getJWTClaimsSet
                  val now     = System.currentTimeMillis() / 1000
                  val expired = claims.getExpirationTime.getTime / 1000 < now
                  if (isValid && !expired) Some(claims)
                  else None
                }
            }
          }
          .handleErrorWith { err =>
            Async[F].delay(
              println(s"[EntraTokenService] JWT verification failed: ${err.getMessage}")
            ) *> Async[F].pure(None)
          }
      // Entra userinfo endpoint
      def fetchUserInfo(token: String): F[UserInfo] =
        val request = GET(
          Uri.unsafeFromString(config.userInfoEndpoint)
        ).withHeaders(
          Authorization(Credentials.Token(AuthScheme.Bearer, token))
        )
        client.expectOr[UserInfo](request) { response =>
          response.bodyText.compile.string.flatMap { body =>
            throw new RuntimeException(
              s"[EntraTokenService] UserInfo fetch failed ${response.status}: $body"
            )
          }
        }
      // Auth redirect URI - same flow as W3ID
      def authUri(state: String): Uri =
        Uri
          .unsafeFromString(config.authorizationEndpoint)
          .withQueryParam("response_type", "code")
          .withQueryParam("client_id", config.clientId)
          .withQueryParam("redirect_uri", config.redirectUri)
          .withQueryParam("scope", config.scope)
          .withQueryParam("state", state)
          .withQueryParam("response_mode", "query")
      // Token exchange - same as W3ID
      def exchangeCodeForToken(code: String): F[TokenResponse] =
        val formData = UrlForm(
          "grant_type"    -> "authorization_code",
          "code"          -> code,
          "redirect_uri"  -> config.redirectUri,
          "client_id"     -> config.clientId,
          "client_secret" -> config.clientSecret
        )
        val request = POST(formData, Uri.unsafeFromString(config.tokenEndpoint))
        client.expectOr[TokenResponse](request) { response =>
          response.bodyText.compile.string.flatMap { body =>
            throw new RuntimeException(
              s"[EntraTokenService] Token exchange failed ${response.status}: $body"
            )
          }
        }
      // Entra does not have introspection - we reuse verifyJwt
      def introspectToken(token: String): F[IntrospectionResponse] =
        verifyJwt(token).map {
          case None =>
            IntrospectionResponse(
              active = false,
              scope = None,
              client_id = None,
              username = None,
              exp = None
            )
          case Some(claims) =>
            IntrospectionResponse(
              active = true,
              scope = Option(claims.getStringClaim("scp"))
                .orElse(Option(claims.getStringClaim("scope"))),
              client_id = Option(claims.getStringClaim("appid"))
                .orElse(Option(claims.getStringClaim("azp"))),
              username = Option(claims.getStringClaim("preferred_username"))
                .orElse(Option(claims.getStringClaim("upn"))),
              exp = Option(claims.getExpirationTime)
                .map(_.getTime / 1000)
            )
        }
      def shouldRefreshToken(token: String): F[Boolean] =
        introspectToken(token).map { introspection =>
          if (!introspection.active) true
          else
            introspection.exp.exists { exp =>
              val now = System.currentTimeMillis() / 1000
              (exp - now) < 300
            }
        }
      def decodeJwt(token: String): Option[UserInfo] =
        scala.util.Try(SignedJWT.parse(token)).toOption.flatMap { jwt =>
          val now     = System.currentTimeMillis() / 1000
          val claims  = jwt.getJWTClaimsSet
          val expired = claims.getExpirationTime.getTime / 1000 < now
          if (expired) None
          else Some(UserInfo.Anonymous)
        }
      def refreshToken(refreshToken: String): F[TokenResponse] =
        val formData = UrlForm(
          "grant_type"    -> "refresh_token",
          "refresh_token" -> refreshToken,
          "client_id"     -> config.clientId,
          "client_secret" -> config.clientSecret
        )
        val request = POST(formData, Uri.unsafeFromString(config.tokenEndpoint))
        client.expectOr[TokenResponse](request) { response =>
          response.bodyText.compile.string.flatMap { body =>
            throw new RuntimeException(
              s"[EntraTokenService] Token refresh failed ${response.status}: $body"
            )
          }
        }
