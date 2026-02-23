// In org.ibm.mcp/ToolHandlers.scala (or within your main mcp file)
package org.ibm.mcp

import cats.effect.IO
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import org.ibm.models.{
  ApiError,
  CallToolResult,
  ContentBlock,
  StructuredContent,
  JsonRpcErrorObject,
  McpErrorType
}
import org.pwharned.database.sql.{Database, Row, FieldBinder}
import org.pwharned.database.sql.Connection._
import org.pwharned.database.sql.SqlDialect
import org.pwharned.database.hkd._
import org.pwharned.database.derive._
import scala.concurrent.ExecutionContext
import scala.util.Try
object ToolHandlers {

  // --- Handler Factory for `endpointFor`-like queries (queryParameterized) ---
  // This is specifically designed for tools whose arguments directly map to Optional[DataType].
  def handlerForEndpointFor[
      ToolParamType <: ToolCallParams, // The specific ToolCallParams type (e.g., NonPoInvoicesForYearTool)
      DataType[_[_]] <: Product // The underlying database entity type (e.g., NonPoInvoicesForYear)
  ](
      limit: Int = 10
  )(using
      dialect: SqlDialect,
      ha: HasArguments[ToolParamType, Optional[
        DataType
      ]], // Arguments are directly Optional[DataType]
      row: Row[Persisted[DataType]], // Row mapper for the result type
      sqlsO: SqlSelect[
        Optional[DataType]
      ], // SqlSelect for the Optional parameters
      fb: FieldBinder[
        Optional[DataType]
      ], // FieldBinder for the Optional parameters
      sqlSelect: SqlSelect[Persisted[DataType]],
      ec: ExecutionContext // For IO.fromFuture
  ): Handler[ToolParamType] = new Handler[ToolParamType] {
    def handle(cmd: ToolParamType): IO[CallToolResult] = {
      val args: Optional[DataType] = ha.getArguments(
        cmd
      ) // Arguments are already in Optional[DataType] format

      val queryIO =
        IO.fromFuture(
          IO(GlobalDatabase.db.withConnection { conn =>
            // Check if all arguments are None to decide between generic query and parameterized query
            if args.productIterator.forall(_ == None) then
              conn.query[Persisted[DataType]](Some(limit))
            else
              conn
                .queryParameterized[Optional[DataType], Persisted[
                  DataType
                ]](args)
                .take(limit)
                .toList
          })
        )

      queryIO.map { t =>
        t.toEither match {
          case Right(data) =>
            CallToolResult(
              content = List(
                ContentBlock(`type` = "text", text = "Query Succesful")
              ),
              structuredContent = Some(StructuredContent(result = data)),
              isError = false
            )
          case Left(apiError) =>
            CallToolResult(
              content = List(
                ContentBlock(
                  `type` = "text",
                  text = apiError.toString()
                )
              ),
              isError = true
            )
        }
      }
    }
  }

}
