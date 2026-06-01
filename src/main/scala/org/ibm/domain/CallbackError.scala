package org.ibm.domain

import cats.effect.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

case class CallbackError(
    error: String,
    message: String
)

object CallbackError {
  implicit val codec: JsonValueCodec[CallbackError] = JsonCodecMaker.make
}
