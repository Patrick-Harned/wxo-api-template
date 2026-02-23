package org.ibm.authz

import cats.effect.*
import cats.implicits.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers._
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.dsl.io.*
import java.util.Base64
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import org.ibm.authz.OIDCConfig
import cats.data.EitherT
import scala.util.Try
import org.http4s.client.blaze.BlazeClientBuilder
import java.net.URLEncoder

case class TokenResponse(
    access_token: String,
    token_type: String,
    expires_in: Option[Int],
    refresh_token: Option[String],
    scope: Option[String]
)

object TokenResponse {
  implicit val codec: JsonValueCodec[TokenResponse] = JsonCodecMaker.make
  implicit val entityDecoder: EntityDecoder[IO, TokenResponse] =
    EntityDecoder.decodeBy(MediaType.application.json) { (m: Media[IO]) =>
      {
        EitherT {
          m.as[String]
            .map(x => readFromString[TokenResponse](x).asRight[DecodeFailure])
        }
      }
    }

}

case class IntrospectionResponse(
    active: Boolean,
    scope: Option[String],
    client_id: Option[String],
    username: Option[String],
    exp: Option[Long]
)

object IntrospectionResponse {
  implicit val codec: JsonValueCodec[IntrospectionResponse] =
    JsonCodecMaker.make
  implicit val entityDecoder: EntityDecoder[IO, IntrospectionResponse] =
    EntityDecoder.decodeBy(MediaType.application.json) { (m: Media[IO]) =>
      {
        EitherT {
          m.as[String]
            .map(x =>
              readFromString[IntrospectionResponse](x).asRight[DecodeFailure]
            )
        }
      }
    }

}

case class UserInfo(
    sub: String,
    name: Option[String],
    email: Option[String],
    preferred_username: Option[String],
    groups: Option[List[String]]
)

object UserInfo {
  implicit val codec: JsonValueCodec[UserInfo]            = JsonCodecMaker.make
  implicit val entityDecoder: EntityDecoder[IO, UserInfo] =
    EntityDecoder.decodeBy(MediaType.application.json) { (m: Media[IO]) =>
      {
        EitherT {
          m.as[String]
            .map(x => readFromString[UserInfo](x).asRight[DecodeFailure])
        }
      }
    }

  val Anonymous = UserInfo("anonymous", None, None, None, None)
}

// Error response model for better error handling
case class ErrorResponse(
    error: String,
    error_description: Option[String]
)

object ErrorResponse {
  implicit val codec: JsonValueCodec[ErrorResponse] = JsonCodecMaker.make
  implicit val entityDecoder: EntityDecoder[IO, ErrorResponse] =
    EntityDecoder.decodeBy(MediaType.application.json) { (m: Media[IO]) =>
      {
        EitherT {
          m.as[String]
            .map(x => readFromString[ErrorResponse](x).asRight[DecodeFailure])
        }
      }
    }
}

class TokenService(config: org.ibm.authz.OIDCConfig)
    extends Http4sClientDsl[IO] {

  val client = BlazeClientBuilder[IO].resource

  def authUri(state: String): Uri = {
    Uri
      .unsafeFromString(config.authorizationEndpoint)
      .withQueryParam("response_type", "code")
      .withQueryParam("client_id", config.clientId)
      .withQueryParam("redirect_uri", config.redirectUri)
      .withQueryParam("scope", config.scope)
      .withQueryParam("state", state)
  }
  def shouldRefreshToken(token: String): IO[Boolean] = {
    introspectToken(token).map { introspection =>
      if (!introspection.active) {
        true // Token invalid - needs refresh
      } else {
        // Check if token expires soon (within 5 minutes)
        introspection.exp.exists { exp =>
          val now = System.currentTimeMillis() / 1000
          (exp - now) < 300 // Less than 5 minutes remaining
        }
      }
    }
  }

  def exchangeCodeForToken(code: String): IO[TokenResponse] = {
    val formData = UrlForm(
      "grant_type"    -> "authorization_code",
      "code"          -> code,
      "redirect_uri"  -> config.redirectUri,
      "client_id"     -> config.clientId,
      "client_secret" -> config.clientSecret
    )

    val request = POST(formData, Uri.unsafeFromString(config.tokenEndpoint))

    client.use(client =>
      client.expectOr[TokenResponse](request) { response =>
        response
          .as[ErrorResponse]
          .flatMap { error =>
            IO.raiseError(
              new RuntimeException(
                s"Token exchange failed: ${error.error} - ${error.error_description.getOrElse("No description")}"
              )
            )
          }
          .handleErrorWith { _ =>
            response.bodyText.compile.string.flatMap { body =>
              IO.raiseError(
                new RuntimeException(
                  s"Token exchange failed with status ${response.status}: $body"
                )
              )
            }
          }
      }
    )
  }
  def introspectToken(token: String): IO[IntrospectionResponse] = {
    val credentials = s"${config.clientId}:${config.clientSecret}"
    val encoded     =
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

    client.use(client =>
      client.expectOr[IntrospectionResponse](request) { response =>
        response.bodyText.compile.string.flatMap { body =>
          IO.raiseError(
            new RuntimeException(
              s"Token introspection failed with status ${response.status}: $body"
            )
          )
        }
      }
    )
  }

  def fetchUserInfo(token: String): IO[UserInfo] = {
    val request = GET(
      Uri.unsafeFromString(config.userInfoEndpoint)
    ).withHeaders(
      Authorization(Credentials.Token(AuthScheme.Bearer, token))
    )

    client.use(client =>
      client.expectOr[UserInfo](request) { response =>
        response.bodyText.compile.string.flatMap { body =>
          IO.raiseError(
            new RuntimeException(
              s"UserInfo fetch failed with status ${response.status}: $body"
            )
          )
        }
      }
    )
  }

  // Helper method to refresh a token if needed
  def refreshToken(refreshToken: String): IO[TokenResponse] = {
    val formData = UrlForm(
      "grant_type"    -> "refresh_token",
      "refresh_token" -> refreshToken,
      "client_id"     -> config.clientId,
      "client_secret" -> config.clientSecret
    )

    val request = POST(formData, Uri.unsafeFromString(config.tokenEndpoint))

    client.use(client =>
      client.expectOr[TokenResponse](request) { response =>
        response.bodyText.compile.string.flatMap { body =>
          IO.raiseError(
            new RuntimeException(
              s"Token refresh failed with status ${response.status}: $body"
            )
          )
        }
      }
    )
  }
}
