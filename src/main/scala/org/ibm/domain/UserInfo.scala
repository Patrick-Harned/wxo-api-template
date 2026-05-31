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

case class UserInfo(
    sub: String,
    name: Option[String],
    email: Option[String],
    preferred_username: Option[String],
    groups: Option[List[String]]
)

object UserInfo {
  implicit val codec: JsonValueCodec[UserInfo] = JsonCodecMaker.make
  implicit val entityDecoder: EntityDecoder[IO, UserInfo] =
    EntityDecoder.decodeBy(MediaType.application.json) { (m: Media[IO]) =>
      EitherT {
        m.as[String]
          .map(x => readFromString[UserInfo](x).asRight[DecodeFailure])
      }
    }

  val Anonymous =
    UserInfo(
      "anonymous",
      None,
      None,
      None,
      None
    )
}
