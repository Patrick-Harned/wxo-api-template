package org.ibm
import java.io.FileOutputStream
import java.net.{Socket, URL}
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.{SSLContext, SSLSocket, TrustManager, X509TrustManager}
import scala.util.{Try, Using}
import scala.util.Failure
import scala.util.Success

object TrustStoreManager {
  def createTrustStoreFromUrl(
      dbUrl: String,
      trustStorePath: String,
      password: String,
      alias: String = "dbcert"
  ): Unit = {

    // Parse the database URL to extract host and port
    val (host, port) = parseDbUrl(dbUrl)

    // Extract certificate from the server
    val certificates = getCertificatesFromServer(host, port)

    // Create truststore with the certificates
    val keyStore = KeyStore.getInstance("JKS")
    keyStore.load(null, password.toCharArray)

    // Add all certificates in the chain
    certificates.zipWithIndex.foreach { case (cert, index) =>
      val certAlias = if (index == 0) alias else s"${alias}_$index"
      keyStore.setCertificateEntry(certAlias, cert)
    }

    // Save to file
    Using(new FileOutputStream(trustStorePath)) { outputStream =>
      keyStore.store(outputStream, password.toCharArray)
    }.get

    println(
      s"Extracted ${certificates.length} certificate(s) and saved to: $trustStorePath"
    )

  }

  def getCertificatesFromServer(
      host: String,
      port: Int
  ): Array[X509Certificate] = {
    var certificates: Array[X509Certificate] = Array.empty

    // Create a trust manager that captures certificates
    val trustManager = new X509TrustManager {
      def checkClientTrusted(
          chain: Array[X509Certificate],
          authType: String
      ): Unit = {}
      def checkServerTrusted(
          chain: Array[X509Certificate],
          authType: String
      ): Unit = {
        certificates = chain
      }
      def getAcceptedIssuers: Array[X509Certificate] = Array.empty
    }

    // Create SSL context with our trust manager
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, Array[TrustManager](trustManager), null)

    // Connect and perform handshake
    Using.Manager { use =>
      println(s"Attempting to connect on $host : $port")
      val socket    = use(new Socket(host, port))
      val sslSocket = use(
        sslContext.getSocketFactory
          .createSocket(socket, host, port, true)
          .asInstanceOf[SSLSocket]
      )

      sslSocket.startHandshake()
      certificates
    }.get
  }

  def parseDbUrl(dbUrl: String): (String, Int) = {
    // Handle different database URL formats
    val pattern =
      """jdbc:(\w+)://([^:/]+)(?::(\d+))?(?:[/:][^:;]*)?(?:[:;].*)?""".r

    dbUrl match {
      case pattern(dbType, host, portStr) =>
        val port =
          Option(portStr).map(_.toInt).getOrElse(getDefaultPort(dbType))
        (host, port)
      case _ =>
        throw new IllegalArgumentException(s"Invalid database URL: $dbUrl")
    }
  }

  def getDefaultPort(dbType: String): Int = dbType.toLowerCase match {
    case "postgresql" | "postgres" => 5432
    case "mysql"                   => 3306
    case "oracle"                  => 1521
    case "sqlserver"               => 1433
    case "mongodb"                 => 27017
    case _                         =>
      throw new IllegalArgumentException(s"Unknown database type: $dbType")
  }
}
