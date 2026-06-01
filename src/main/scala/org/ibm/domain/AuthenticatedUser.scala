package org.ibm.domain
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

case class AuthenticatedUser(
    userInfo: UserInfo,
    token: String,
    introspectionResponse: Option[IntrospectionResponse]
)

object AuthenticatedUser {
  implicit val codec: JsonValueCodec[AuthenticatedUser] = JsonCodecMaker.make
}
