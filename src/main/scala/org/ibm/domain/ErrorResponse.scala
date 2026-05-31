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

// Error response model for better error handling
case class ErrorResponse(
    error: String,
    error_description: Option[String]
)

object ErrorResponse {
  implicit val codec: JsonValueCodec[ErrorResponse] = JsonCodecMaker.make
  implicit val entityDecoder: EntityDecoder[IO, ErrorResponse] =
    EntityDecoder.decodeBy(MediaType.application.json) { (m: Media[IO]) =>
      EitherT {
        m.as[String]
          .map(x => readFromString[ErrorResponse](x).asRight[DecodeFailure])
      }
    }
}
