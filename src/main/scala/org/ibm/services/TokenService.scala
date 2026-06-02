package org.ibm.services

import cats.effect.*
import cats.implicits.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import org.http4s._
import org.http4s.client.Client
import org.http4s.headers._
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.dsl.io.*
import java.util.Base64
import org.http4s.Uri
import cats.effect.kernel.Async
import java.net.URLEncoder
import pdi.jwt.Jwt
import pdi.jwt.JwtOptions
import org.ibm.domain._
import org.ibm.config._

trait TokenService[F[_]]:

  def validateToken(token: String): F[Option[AuthenticatedUser]]
  def decodeJwt(token: String): Option[UserInfo]
  def authUri(state: String): Uri
  def shouldRefreshToken(token: String): F[Boolean]

  def exchangeCodeForToken(code: String): F[TokenResponse]
  def introspectToken(token: String): F[IntrospectionResponse]
  def fetchUserInfo(token: String): F[UserInfo]

  def refreshToken(refreshToken: String): F[TokenResponse]

object TokenService:
  def live[F[_]: Async](client: Client[F], config: OIDCConfig): TokenService[F] =
    new TokenService[F] with Http4sClientDsl[F] with JsoniterInstances:
      def introspectToken(
          token: String
      ): F[IntrospectionResponse] = {
        val credentials = s"${config.clientId}:${config.clientSecret}"
        val encoded =
          Base64.getEncoder.encodeToString(credentials.getBytes("UTF-8"))

        val formData = Map(
          "token"         -> token,
          "client_id"     -> config.clientId,
          "grant_type"    -> "authorization_code",
          "scope"         -> "openid",
          "redirect_uri"  -> config.redirectUri,
          "client_secret" -> config.clientSecret
        )

        val entity = formData.toList
          .map { case (k, v) =>
            s"${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
          }
          .mkString("&")
        val request = POST(
          entity,
          Uri.unsafeFromString(config.introspectionEndpoint)
        ).withHeaders(
          Authorization(Credentials.Token(AuthScheme.Basic, encoded)),
          `Content-Type`(MediaType.application.`x-www-form-urlencoded`)
        )
        client.expectOr[IntrospectionResponse](request) { response =>
          response.bodyText.compile.string.flatMap { body =>
            throw new RuntimeException(
              s"Token introspection failed with status ${response.status}: $body"
            )
          }
        }

      }

      def validateToken(
          token: String
      ): F[Option[AuthenticatedUser]] =
        introspectToken(token)
          .flatMap { introspection =>
            fetchUserInfo(token).map(userInfo =>
              Some(AuthenticatedUser(userInfo, token, Some(introspection)))
            )
          }

      def decodeJwt(token: String): Option[UserInfo] =
        Jwt
          .decode(token, JwtOptions(signature = false))
          .toOption
          .flatMap { claim =>
            val now = System.currentTimeMillis() / 1000
            // check expiry first - if expired, return None
            val expired = claim.expiration.exists(_ < now)
            if (expired) None
            else {
              Some(UserInfo.Anonymous)
            }
          }

      def authUri(state: String): Uri =
        Uri
          .unsafeFromString(config.authorizationEndpoint)
          .withQueryParam("response_type", "code")
          .withQueryParam("client_id", config.clientId)
          .withQueryParam("redirect_uri", config.redirectUri)
          .withQueryParam("scope", config.scope)
          .withQueryParam("state", state)
      def shouldRefreshToken(
          token: String
      ): F[Boolean] =
        introspectToken(token).map { introspection =>
          if (!introspection.active) {
            true // Token invalid - needs refresh
          }
          else {
            // Check if token expires soon (within 5 minutes)
            introspection.exp.exists { exp =>
              val now = System.currentTimeMillis() / 1000
              (exp - now) < 300 // Less than 5 minutes remaining
            }
          }
        }

      def exchangeCodeForToken(
          code: String
      ): F[TokenResponse] = {
        val formData = UrlForm(
          "grant_type"    -> "authorization_code",
          "code"          -> code,
          "redirect_uri"  -> config.redirectUri,
          "client_id"     -> config.clientId,
          "client_secret" -> config.clientSecret
        )

        val request = POST(formData, Uri.unsafeFromString(config.tokenEndpoint))

        client.expectOr[TokenResponse](request) { response =>
          response
            .as[ErrorResponse]
            .flatMap { error =>
              throw new RuntimeException(
                s"Token exchange failed: ${error.error} - ${error.error_description.getOrElse("No description")}"
              )
            }
            .handleErrorWith { _ =>
              response.bodyText.compile.string.flatMap { body =>
                throw new RuntimeException(
                  s"Token exchange failed with status ${response.status}: $body"
                )
              }
            }
        }
      }

      def fetchUserInfo(token: String): F[UserInfo] = {
        val request = GET(
          Uri.unsafeFromString(config.userInfoEndpoint)
        ).withHeaders(
          Authorization(Credentials.Token(AuthScheme.Bearer, token))
        )

        client.expectOr[UserInfo](request) { response =>
          response.bodyText.compile.string.flatMap { body =>
            throw new RuntimeException(
              s"UserInfo fetch failed with status ${response.status}: $body"
            )
          }
        }

      }

      def refreshToken(refreshToken: String): F[TokenResponse] = {
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
              s"Token refresh failed with status ${response.status}: $body"
            )
          }
        }

      }
