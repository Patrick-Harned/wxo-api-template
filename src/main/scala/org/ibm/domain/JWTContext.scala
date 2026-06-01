package org.ibm.domain

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

case class JwtContext(
    wxo_name: String,
    wxo_role: String = "user",
    email: Option[String] = Some("unknown@example.com"),
    displayName: String,
    location: String = "unknown"
)

object JwtContext:
  given JsonValueCodec[JwtContext] = JsonCodecMaker.make
