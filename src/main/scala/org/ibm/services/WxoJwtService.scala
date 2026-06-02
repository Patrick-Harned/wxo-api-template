package org.ibm.services

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import org.ibm.domain.JsonMap.{given, _}
import org.ibm.domain._
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
import cats.effect.kernel.Async
import org.ibm.config._
import cats.implicits.*
import org.ibm.authz._
opaque type JwtToken = String
object JwtToken:
  def apply(s: String): JwtToken   = s
  def unapply(j: JwtToken): String = j

end JwtToken
trait WxoJwtService[F[_]]:
  def createJwtToken(a: AuthenticatedUser): F[JwtToken]

object WxoJwtService:
  def live[F[_]: Async](config: JwtConfig, ts: TokenService[F]): WxoJwtService[F] =
    new WxoJwtService[F] {
      Security.addProvider(BouncyCastleProvider())

      private lazy val privateKey: PrivateKey =
        loadPrivateKey(config.privateKey, config.isKeyContent)
      private lazy val ibmPublicKey: PublicKey =
        loadPublicKey(config.ibmPublicKey, config.isKeyContent)
      def createJwtToken(a: AuthenticatedUser): F[JwtToken] =
        ts.validateToken(a.token)
          .recover { case _ => None } // if validation fails, fall back to None
          .map { validationResult =>
            val userPayload = UserPayload(sso_token = a.token)
            val jsonString  = writeToString(userPayload)
            val tokenHash = MessageDigest
              .getInstance("SHA-256")
              .digest(a.token.getBytes("UTF-8"))
              .take(16)
              .map("%02x".format(_))
              .mkString
            val cacheUserId      = s"${a.userInfo.sub}_${tokenHash}"
            val encryptedPayload = EncryptionStrategy.fromEnv.encrypt(jsonString, ibmPublicKey)
            val context = JwtContext(
              wxo_name = a.userInfo.sub,
              displayName = a.userInfo.name.getOrElse(a.userInfo.sub)
            )
            val jwtContent = Map[String, Any](
              "sub"          -> cacheUserId,
              "woUserId"     -> cacheUserId,
              "woTenantId"   -> config.orchestrationId,
              "user_payload" -> encryptedPayload,
              "context" -> Map(
                "wxo_name"    -> context.wxo_name,
                "wxo_role"    -> context.wxo_role,
                "email"       -> context.email,
                "displayName" -> context.displayName,
                "location"    -> context.location
              )
            )
            val expiration = validationResult
              .flatMap(_.introspectionResponse)
              .flatMap(_.exp)
              .getOrElse(
                System.currentTimeMillis() / 1000 + sys.env
                  .get("TOKEN_EXPIRY")
                  .flatMap(_.toLongOption)
                  .getOrElse(600L)
              )
            val claim = JwtClaim(
              content = writeToString[Map[String, Any]](jwtContent),
              expiration = Some(expiration)
            )
            JwtToken.apply(Jwt.encode(claim, privateKey, JwtAlgorithm.RS256))
          }

      private def loadPrivateKey(
          keySource: String,
          isContent: Boolean
      ): PrivateKey = {
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
      }
      private def loadPublicKey(keySource: String, isContent: Boolean): PublicKey = {
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
      }
    }
