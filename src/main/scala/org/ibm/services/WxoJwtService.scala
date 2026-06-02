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
  def createJwtToken(a: AuthenticatedUser): F[Either[String, JwtToken]]
object WxoJwtService:
  def live[F[_]: Async](config: JwtConfig, ts: TokenService[F]): WxoJwtService[F] =
    new WxoJwtService[F] {
      Security.addProvider(BouncyCastleProvider())
      // Eager key loading - errors captured at construction time and logged immediately
      private val keyLoadResult: Either[String, (PrivateKey, PublicKey)] =
        Try {
          val pk   = loadPrivateKey(config.privateKey, config.isKeyContent)
          val pubk = loadPublicKey(config.ibmPublicKey, config.isKeyContent)
          (pk, pubk)
        }.toEither.left.map { err =>
          val msg = s"[WxoJwtService] FATAL: Failed to load RSA keys at startup: ${err.getMessage}"
          println(msg)
          msg
        }
      // Log success at construction time too
      keyLoadResult.foreach { _ =>
        println("[WxoJwtService] RSA keys loaded successfully at startup")
      }
      def createJwtToken(a: AuthenticatedUser): F[Either[String, JwtToken]] =
        keyLoadResult match {
          case Left(_) =>
            // Keys failed to load at startup - return a user-facing error message
            Async[F].pure(
              Left(
                "The authentication service is not configured correctly. " +
                  "Please contact your administrator."
              )
            )
          case Right((privateKey, ibmPublicKey)) =>
            ts.validateToken(a.token)
              .recover { case _ => None }
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
                Right(JwtToken.apply(Jwt.encode(claim, privateKey, JwtAlgorithm.RS256)))
              }
              .handleErrorWith { err =>
                Async[F].delay(
                  println(
                    s"[WxoJwtService] Unexpected error during JWT creation: ${err.getMessage}"
                  )
                ) *> Async[F].pure(
                  Left("An unexpected error occurred while creating your session.")
                )
              }
        }
      private def loadPrivateKey(keySource: String, isContent: Boolean): PrivateKey = {
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
