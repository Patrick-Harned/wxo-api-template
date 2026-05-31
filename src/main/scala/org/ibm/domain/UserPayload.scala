package org.ibm.domain
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

case class UserPayload(
    sso_token: String
)

object UserPayload:
  given JsonValueCodec[UserPayload] = JsonCodecMaker.make
