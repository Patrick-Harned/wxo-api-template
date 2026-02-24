package org.ibm
import java.sql.{Connection, DriverManager, PreparedStatement, Statement, SQLException}
import java.time.Instant
import java.time.format.DateTimeFormatter
import cats.effect.IO
import sttp.tapir.*
import scala.util.Using
import sttp.tapir.server.ServerEndpoint
import org.ibm.authz.OIDCAuthMiddleware
import org.ibm.TelCodec.jsonBody
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import cats.effect.Resource
import org.ibm.models.ApiError
import scala.io.Source
import org.pwharned.json.{JsonDeserializer, JsonSerializer}
import org.pwharned.database.hkd._
given PrimaryKeySchema: Schema[PrimaryKey[Int]] =
  Schema.schemaForBigInt
    .as[PrimaryKey[Int]]
    .description("Autoincrementing integer primary key")

def initializeDb(conn: Connection): IO[Unit] =
  IO.blocking { // Add .blocking
    Using(conn.createStatement()) { stmt =>
      stmt.execute("PRAGMA journal_mode=WAL")   // Keep journal in RAM
      stmt.execute("PRAGMA synchronous=NORMAL") // Less syncing
      stmt.execute("PRAGMA temp_store=MEMORY")  // Temp data in RAM
      stmt.execute("PRAGMA cache_size=10000")   // Larger cache
      conn.setAutoCommit(true)
      val resources = Source.fromResource("schema.sql").getLines().mkString.split(";")

      resources.foreach { x =>
        println(x)
        println(stmt.execute(x))

      }
    }.get
  }

def connectionResource(url: String): Resource[IO, Connection] =
  Resource.make(
    IO.blocking { // Add .blocking
      Class.forName("org.sqlite.JDBC")
      DriverManager.getConnection(url)
    }
  )(conn =>
    IO.blocking(conn.close())
      .handleErrorWith(e => // Add .blocking
        IO.println(s"Error closing database connection: $e")
      )
  )

lazy val initializeFeedBackService =
  OIDCAuthMiddleware.authenticatedEndpoint
    .description("initalize feedback service")
    .in("api" / "feedback" / "initialize")
    .out(stringBody)
    .serverLogic { user => formData =>
      val dbUrl =
        s"jdbc:sqlite:${sys.env.getOrElse("DB_PATH", "/app/storage/db/mydb.sqlite")}"
      user.userInfo.email.map(x => x.toLowerCase()) match

        case Some(value) if value == "patrick.harned@ibm.com" =>
          connectionResource(dbUrl)
            .use { conn =>
              for {
                _ <- initializeDb(conn)
              } yield Right("Feedback service initialized successfully!")
            }
            .handleErrorWith { e =>
              IO.println(s"Database operation failed: ${e.getMessage}")
                .as(
                  Left(ApiError(s"Failed to submit feedback: ${e.getMessage}"))
                )
            }

        case _ => IO.pure(Left(ApiError("Unauthorized")))
    }
