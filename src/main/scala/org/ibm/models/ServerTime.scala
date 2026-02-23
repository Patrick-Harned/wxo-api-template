package org.ibm.models
import sttp.tapir.Schema
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

case class CurrentTime(time: String)
object CurrentTime:
  given JsonValueCodec[CurrentTime] = JsonCodecMaker.make
  given Schema[CurrentTime]         = Schema.derived
