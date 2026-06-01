package org.ibm.domain

import java.nio.ByteBuffer

import cats.effect.Concurrent

import cats.Applicative
import cats.effect.Sync
import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray, writeToArray}
import org.http4s.{
  DecodeResult,
  EntityDecoder,
  EntityEncoder,
  MalformedMessageBodyFailure,
  MediaType
}
import org.http4s.headers.`Content-Type`

import scala.util.{Failure, Success, Try}
import cats.syntax.applicative.*
import org.http4s.DecodeFailure
trait JsoniterInstances {
  implicit def jsoniterEntityEncoder[F[_]: Applicative, A: JsonValueCodec]: EntityEncoder[F, A] =
    EntityEncoder
      .byteArrayEncoder[F]
      .contramap[A](writeToArray(_))
      .withContentType(`Content-Type`(MediaType.application.json))
  implicit def jsoniterEntityDecoder[F[_]: Concurrent, A: JsonValueCodec]: EntityDecoder[F, A] =
    EntityDecoder.decodeBy(MediaType.application.json) { msg =>
      EntityDecoder.collectBinary(msg).flatMap { chunk =>
        val bytes = chunk.toArray // no .force needed
        if (bytes.nonEmpty) {
          Try(readFromArray[A](bytes)) match {
            case Success(json) =>
              DecodeResult.success(json.pure[F]) // wrap in F
            case Failure(pf) =>
              DecodeResult.failure(
                (MalformedMessageBodyFailure("Invalid JSON", Some(pf)): DecodeFailure).pure[F]
              )
          }
        }
        else {
          DecodeResult.failure(
            (MalformedMessageBodyFailure("Invalid JSON: empty body", None): DecodeFailure).pure[F]
          )
        }
      }
    }
}
