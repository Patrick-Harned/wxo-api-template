package org.ibm.tls

case class TLSConfig(
    enabled: Boolean,
    keystorePath: String,
    keystorePassword: String,
    keyManagerPassword: String,
    keystoreType: String = "PKCS12"
)

object TLSConfig {
  def fromEnv: TLSConfig = TLSConfig(
    enabled = sys.env.get("TLS_ENABLED").exists(_.toLowerCase == "true"),
    keystorePath =
      sys.env.getOrElse("TLS_KEYSTORE_PATH", "./certs/keystore.p12"),
    keystorePassword = sys.env.getOrElse("TLS_KEYSTORE_PASSWORD", "changeit"),
    keyManagerPassword = sys.env.getOrElse("TLS_KEY_PASSWORD", "changeit"),
    keystoreType = sys.env.getOrElse("TLS_KEYSTORE_TYPE", "PKCS12")
  )
}
