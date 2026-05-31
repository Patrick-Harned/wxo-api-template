package org.ibm.config

import java.util.Base64
import scala.util.Try
case class JwtConfig(
    wxoTenantId: String,
    privateKey: String,
    ibmPublicKey: String,
    isKeyContent: Boolean,
    tokenExpirationSeconds: Long,
    orchestrationId: String
)

object JwtConfig:
  def fromEnv: Try[JwtConfig] =
    Try {
      val privateKeyBase64 = sys.env.getOrElse(
        "WXO_PRIVATE_KEY_BASE64",
        throw new IllegalStateException(
          "WXO_PRIVATE_KEY_BASE64 environment variable not set"
        )
      )
      val ibmPublicKeyBase64 = sys.env.getOrElse(
        "WXO_IBM_PUBLIC_KEY_BASE64",
        throw new IllegalStateException(
          "WXO_IBM_PUBLIC_KEY_BASE64 environment variable not set"
        )
      )

      JwtConfig(
        wxoTenantId = sys.env.getOrElse(
          "WXO_TENANT_ID",
          throw new RuntimeException("WXO_TENANT_ID not set")
        ),
        privateKey = new String(Base64.getDecoder.decode(privateKeyBase64), "UTF-8"),
        ibmPublicKey = new String(Base64.getDecoder.decode(ibmPublicKeyBase64), "UTF-8"),
        isKeyContent = true,
        tokenExpirationSeconds = sys.env
          .get("WXO_TOKEN_EXPIRATION")
          .flatMap(_.toLongOption)
          .getOrElse(3600L),
        orchestrationId = sys.env.getOrElse(
          "WXO_ORCHESTRATION_ID",
          throw new RuntimeException("WXO_ORCHESTRATION_ID not set")
        )
      )
    }
