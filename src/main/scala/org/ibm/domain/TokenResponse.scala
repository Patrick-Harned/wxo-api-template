package org.ibm.domain

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
import cats.data.EitherT
import scala.util.Try
import org.http4s.client.blaze.BlazeClientBuilder
import java.net.URLEncoder
import pdi.jwt.Jwt
import pdi.jwt.JwtOptions
import sttp.tapir.Schema

case class TokenResponse(
    access_token: String,
    token_type: String,
    expires_in: Option[Int],
    refresh_token: Option[String],
    scope: Option[String]
)

object TokenResponse {
  given Schema[TokenResponse]                       = Schema.derived
  implicit val codec: JsonValueCodec[TokenResponse] = JsonCodecMaker.make
  implicit val entityDecoder: EntityDecoder[IO, TokenResponse] =
    EntityDecoder.decodeBy(MediaType.application.json) { (m: Media[IO]) =>
      EitherT {
        m.as[String]
          .map(x => readFromString[TokenResponse](x).asRight[DecodeFailure])
      }
    }

}
