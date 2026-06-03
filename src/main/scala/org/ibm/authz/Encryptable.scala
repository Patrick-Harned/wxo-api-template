package org.ibm.authz
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.{KeyFactory, PrivateKey, PublicKey, Security}
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
// Register provider once at object initialization
private object CryptoSetup:
  lazy val init: Unit =
    if Security.getProvider("BC") == null then Security.addProvider(new BouncyCastleProvider())
sealed trait EncryptionStrategy
sealed abstract class SHA1 private[authz] ()   extends EncryptionStrategy
sealed abstract class SHA256 private[authz] () extends EncryptionStrategy
trait Encryptable[A <: EncryptionStrategy]:
  def encrypt(input: String, publicKey: PublicKey): String
object Encryptable:
  private def chunkSize(keyBitLength: Int, hashBytes: Int): Int =
    (keyBitLength / 8) - 2 - (2 * hashBytes)
  private def encryptChunked(
      dataBytes: Array[Byte],
      publicKey: PublicKey,
      cipher: Cipher,
      oaepParams: OAEPParameterSpec,
      maxChunk: Int
  ): Either[Throwable, String] =
    scala.util.Try {
      val encrypted = dataBytes
        .grouped(maxChunk)
        .map { chunk =>
          cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParams)
          cipher.doFinal(chunk)
        }
        .foldLeft(Array.emptyByteArray)(_ ++ _)
      Base64.getEncoder.encodeToString(encrypted)
    }.toEither
  given sha1Encryptable: Encryptable[SHA1] with
    def encrypt(input: String, publicKey: PublicKey): String =
      CryptoSetup.init
      val oaepParams = new OAEPParameterSpec(
        "SHA-1",
        "MGF1",
        MGF1ParameterSpec.SHA1,
        OAEPParameterSpec.DEFAULT.getPSource
      )
      val cipher       = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding", "BC")
      val keyBitLength = publicKey.asInstanceOf[RSAPublicKey].getModulus.bitLength()
      val maxChunk     = chunkSize(keyBitLength, hashBytes = 20)
      encryptChunked(input.getBytes("UTF-8"), publicKey, cipher, oaepParams, maxChunk)
        .fold(e => throw e, identity)
  given sha256Encryptable: Encryptable[SHA256] with
    def encrypt(input: String, publicKey: PublicKey): String =
      CryptoSetup.init
      val oaepParams = new OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        MGF1ParameterSpec.SHA256,
        OAEPParameterSpec.DEFAULT.getPSource
      )
      val cipher       = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding", "BC")
      val keyBitLength = publicKey.asInstanceOf[RSAPublicKey].getModulus.bitLength()
      val maxChunk     = chunkSize(keyBitLength, hashBytes = 32)
      encryptChunked(input.getBytes("UTF-8"), publicKey, cipher, oaepParams, maxChunk)
        .fold(e => throw e, identity)
object EncryptionStrategy:
  def fromEnv: Encryptable[? <: EncryptionStrategy] =
    sys.env.get("ENCRYPTION_STRATEGY") match
      case Some("SHA1")   => summon[Encryptable[SHA1]]
      case Some("SHA256") => summon[Encryptable[SHA256]]
      case _              => summon[Encryptable[SHA256]]
