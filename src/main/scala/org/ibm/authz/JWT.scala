package org.ibm.authz

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import org.ibm.models.JsonMap.{given, _}
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.{KeyFactory, PrivateKey, PublicKey, Security}
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.time.Instant
import java.util.{Base64, UUID}
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import java.security.spec.MGF1ParameterSpec
import scala.io.Source
import scala.util.{Try, Using}
import cats.effect.unsafe.implicits.global
import scala.concurrent.Future
import java.security.MessageDigest
// ============================================================================
// Domain Models
// ============================================================================

case class UserPayload(
    sso_token: String
)
import java.util.Base64
import javax.crypto.{Cipher}

object UserPayload:
  given JsonValueCodec[UserPayload] = JsonCodecMaker.make

case class JwtContext(
    wxo_name: String,
    wxo_role: String = "user",
    email: Option[String] = Some("unknown@example.com"),
    displayName: String,
    location: String = "unknown"
)

object JwtContext:
  given JsonValueCodec[JwtContext] = JsonCodecMaker.make

case class JwtConfig(
    wxoTenantId: String,
    privateKey: String,
    ibmPublicKey: String,
    isKeyContent: Boolean,
    tokenExpirationSeconds: Long,
    orchestrationId: String
)

// ============================================================================
// Configuration Loader
// ============================================================================

object JwtConfig:
  def fromEnvironmentBase64(): Try[JwtConfig] = Try {
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
      privateKey =
        new String(Base64.getDecoder.decode(privateKeyBase64), "UTF-8"),
      ibmPublicKey =
        new String(Base64.getDecoder.decode(ibmPublicKeyBase64), "UTF-8"),
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

// ============================================================================
// JWT Service
// ============================================================================

class WxoJwtService(config: JwtConfig):

  Security.addProvider(BouncyCastleProvider())

  private lazy val privateKey: PrivateKey =
    loadPrivateKey(config.privateKey, config.isKeyContent)
  private lazy val ibmPublicKey: PublicKey =
    loadPublicKey(config.ibmPublicKey, config.isKeyContent)

  /** Creates a JWT token. This method shows EXACTLY what goes into the JWT.
    * Read this top-to-bottom to understand the token structure.
    */
  def createJwtToken(
      userInfo: UserInfo,
      ssoToken: String
  ): cats.effect.IO[String] = {
    val oidc: OIDCConfig = OIDCConfig.fromEnv
    val ts: TokenService = TokenService(oidc)
    ts
      .introspectToken(ssoToken)
      .map(x =>
        x.active match {
          case true =>

          {

            // Step 1: Create the payload that gets encrypted
            val userPayload = UserPayload(
              sso_token = ssoToken
            )
            val jsonString = writeToString(userPayload)
            val tokenHash  = MessageDigest
              .getInstance("SHA-256")
              .digest(ssoToken.getBytes("UTF-8"))
              .take(16)
              .map("%02x".format(_))
              .mkString

            // Combine real user ID + token hash
            val cacheUserId      = s"${userInfo.sub}_${tokenHash}"
            val encryptedPayload =
              EncryptionStrategy.fromEnv.encrypt(jsonString, ibmPublicKey)

            // Step 2: Create the context
            val context = JwtContext(
              wxo_name = userInfo.sub,
              displayName = userInfo.name.getOrElse(userInfo.sub)
            )

            // Step 3: Build the complete JWT content - THIS IS WHAT GOES IN THE TOKEN
            val jwtContent = Map[String, Any](
              "sub"          -> cacheUserId,
              "woUserId"     -> cacheUserId,
              "woTenantId"   -> config.orchestrationId,
              "user_payload" -> encryptedPayload,
              "context"      -> Map(
                "wxo_name"    -> context.wxo_name,
                "wxo_role"    -> context.wxo_role,
                "email"       -> context.email,
                "displayName" -> context.displayName,
                "location"    -> context.location
              )
            )

            // Step 4: Create and sign the JWT
            val expiresAt =
              Instant.now.getEpochSecond + config.tokenExpirationSeconds
            val claim = JwtClaim(
              content = writeToString[Map[String, Any]](jwtContent),
              expiration = x.exp
            )

            Jwt.encode(claim, privateKey, JwtAlgorithm.RS256)
          }
          case _ => throw new RuntimeException("Token was invalid.")
        }
      )
  }

  private def loadPrivateKey(
      keySource: String,
      isContent: Boolean
  ): PrivateKey =
    val keyContent =
      if (isContent) keySource
      else Using(Source.fromFile(keySource))(_.mkString).get
    val cleanedKey = keyContent
      .replace("-----BEGIN PRIVATE KEY-----", "")
      .replace("-----END PRIVATE KEY-----", "")
      .replace("-----BEGIN RSA PRIVATE KEY-----", "")
      .replace("-----END RSA PRIVATE KEY-----", "")
      .replaceAll("\\s", "")
    val decoded = Base64.getDecoder.decode(cleanedKey)
    val keySpec = PKCS8EncodedKeySpec(decoded)
    KeyFactory.getInstance("RSA").generatePrivate(keySpec)

  private def loadPublicKey(keySource: String, isContent: Boolean): PublicKey =
    val keyContent =
      if (isContent) keySource
      else Using(Source.fromFile(keySource))(_.mkString).get
    val cleanedKey = keyContent
      .replace("-----BEGIN PUBLIC KEY-----", "")
      .replace("-----END PUBLIC KEY-----", "")
      .replaceAll("\\s", "")
    val decoded = Base64.getDecoder.decode(cleanedKey)
    val keySpec = X509EncodedKeySpec(decoded)
    KeyFactory.getInstance("RSA").generatePublic(keySpec)
