package org.ibm.tls

import cats.effect.*
import org.http4s.blaze.server.BlazeServerBuilder
import java.io.FileInputStream
import java.security.{KeyStore, Security}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import scala.util.{Try, Success, Failure}

object TLSSupport {

  def loadSSLContext(config: TLSConfig): SSLContext = {
    // Load keystore
    val keyStore       = KeyStore.getInstance(config.keystoreType)
    val keystoreStream = new FileInputStream(config.keystorePath)

    try {
      keyStore.load(keystoreStream, config.keystorePassword.toCharArray)
    } finally {
      keystoreStream.close()
    }

    // Initialize key manager factory
    val keyManagerFactory = KeyManagerFactory.getInstance(
      KeyManagerFactory.getDefaultAlgorithm
    )
    keyManagerFactory.init(keyStore, config.keyManagerPassword.toCharArray)

    // Initialize trust manager factory
    val trustManagerFactory = TrustManagerFactory.getInstance(
      TrustManagerFactory.getDefaultAlgorithm
    )
    trustManagerFactory.init(keyStore)

    // Create SSL context
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(
      keyManagerFactory.getKeyManagers,
      trustManagerFactory.getTrustManagers,
      new java.security.SecureRandom
    )

    sslContext
  }

  // Helper to check if certificate exists
  def certificateExists(path: String): Boolean = {
    new java.io.File(path).exists()
  }

  // Helper to ensure certificate exists or generate it
}
