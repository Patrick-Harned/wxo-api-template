package org.ibm.mcp
import org.pwharned.database.hkd.*
import org.pwharned.database.sql.*
import scala.language.implicitConversions
import org.pwharned.database.*

import org.pwharned.config.EnvLoader
import org.pwharned.database.sql.Connection.*
object GlobalDatabase:
  given DbTypeMapper        = Db2TypeMapper
  given dialect: SqlDialect = Db2Dialect
  val connectionDetails     =
    EnvLoader.loadFromFileOrEnv[ConnectionDetails](".env") match
      case Left(value)  => throw new RuntimeException(value)
      case Right(value) => value
  given db: Database = Database.apply(connectionDetails)
  db.createPool(connectionDetails)
