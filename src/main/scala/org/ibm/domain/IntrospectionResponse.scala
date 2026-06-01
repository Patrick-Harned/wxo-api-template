package org.ibm.domain
import cats.effect._

import cats.implicits.catsSyntaxEitherId
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import org.http4s.EntityDecoder
import org.http4s.MediaType
import org.http4s.Media
import cats.data.EitherT
import org.http4s.DecodeFailure
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
  given EntityDecoder[IO, IntrospectionResponse] =
    EntityDecoder.decodeBy(MediaType.application.json) { (m: Media[IO]) =>
      EitherT {
        m.as[String]
          .map(x => readFromString[IntrospectionResponse](x).asRight[DecodeFailure])
      }
    }

}
