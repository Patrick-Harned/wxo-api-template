package org.ibm.authz

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.{KeyFactory, PrivateKey, PublicKey, Security}
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.util.Base64

sealed trait EncryptionStrategy
trait SHA1   extends EncryptionStrategy
trait SHA256 extends EncryptionStrategy
trait Encryptable[A <: EncryptionStrategy]:
  def encrypt(input: String, publicKey: PublicKey): String

object Encryptable:
  given sha1Encryptable: Encryptable[SHA1] with
    def encrypt(input: String, publicKey: PublicKey): String =
      val dataBytes = input.getBytes("UTF-8")

      val cipher     = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
      val oaepParams = new OAEPParameterSpec(
        "SHA-1",
        "MGF1",
        MGF1ParameterSpec.SHA1,
        OAEPParameterSpec.DEFAULT.getPSource
      )
      cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParams)

      val encrypted = cipher.doFinal(dataBytes)
      Base64.getEncoder.encodeToString(encrypted)
  given sha256Encryptable: Encryptable[SHA256] with
    def encrypt(input: String, publicKey: PublicKey): String =
      val dataBytes = input.getBytes("UTF-8")

      val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
      val oaepParams = new OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        MGF1ParameterSpec.SHA256,
        OAEPParameterSpec.DEFAULT.getPSource
      )
      cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParams)

      val encrypted = cipher.doFinal(dataBytes)
      Base64.getEncoder.encodeToString(encrypted)

object EncryptionStrategy {

  def fromEnv =
    sys.env.get("ENCRYPTION_STRATEGY") match
      case Some("SHA1") =>
        summon[Encryptable[SHA1]]
      case Some("SHA256") => summon[Encryptable[SHA256]]
      case _              =>
        summon[Encryptable[SHA256]]
}
