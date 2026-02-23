package org.ibm

import sttp.tapir._
import sttp.tapir.Codec.JsonCodec
import sttp.tapir.DecodeResult.{Error, Value}
import sttp.tapir.DecodeResult.Error.{JsonDecodeException, JsonError}
import sttp.tapir.Schema.SName
import sttp.tapir.generic.auto._
import org.pwharned.json._
object TelCodec {
  def jsonBody[T: JsonDeserializer: JsonSerializer: Schema]
      : EndpointIO.Body[String, T] = stringBodyUtf8AnyFormat(jsonCodec[T])

  def jsonBodyWithRaw[T: JsonSerializer: JsonDeserializer: Schema]
      : EndpointIO.Body[String, (String, T)] = stringBodyUtf8AnyFormat(
    implicitly[JsonCodec[(String, T)]]
  )

  implicit def jsonCodec[T: Schema: JsonDeserializer](using
      deser: JsonDeserializer[T],
      ser: JsonSerializer[T]
  ): JsonCodec[T] = {
    sttp.tapir.Codec.json[T] { s =>
      scala.util.Try { deser.decode(s.getBytes, 0) }.toEither match {
        case Right(v)    => Value(v._1)
        case Left(error) =>
          Error(
            s,
            JsonDecodeException(
              errors = List(
                JsonError(s, path = List.empty),
                JsonError(error.toString, path = List.empty)
              ),
              underlying = new Exception(error.toString)
            )
          )
      }

    } { t => ser.serialize(t) }
  }

}
