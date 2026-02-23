package org.ibm.mcp

import cats.syntax.all.*
import cats.effect._
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import org.ibm.models._
import org.pwharned.database.hkd._
import org.pwharned.database.sql.Connection._
import scala.concurrent.ExecutionContext
import scala.util.Try
import scala.compiletime.{erasedValue, summonInline}
import org.ibm.PurchaseOrderCount
import org.pwharned.json.{JsonDeserializer, JsonSerializer}
import org.ibm.models.JsonMap.{given, _}
sealed trait ToolCallParams

case class Test(value: String) extends ToolCallParams

object ToolCallParams:
  implicit val classUseOneTypeCodec: JsonValueCodec[ToolCallParams] =
    JsonCodecMaker.make[ToolCallParams](
      CodecMakerConfig.withDiscriminatorFieldName(Some("name"))
    )
type AllCommands = EmptyTuple
object Dispatcher:
  inline def dispatchTuple[Ts <: Tuple](
      cmd: ToolCallParams
  ): cats.effect.IO[Either[JsonRpcErrorObject, CallToolResult]] =
    inline erasedValue[Ts] match
      case _: (t *: ts) =>
        if cmd.isInstanceOf[t] then
          val concrete = cmd.asInstanceOf[t]
          val h        = summonInline[Handler[t]]
          h.handle(concrete).attempt.map {
            case Right(result) =>
              Right(result)
            case Left(e) =>
              Left(McpErrorType.UnhandledError(e.getMessage).toRpcErrorObject)
          }
        else dispatchTuple[ts](cmd)
      case _: EmptyTuple =>
        IO.pure(
          Left(McpErrorType.MethodNotFound("unknown method").toRpcErrorObject)
        )

  def dispatch(
      cmd: ToolCallParams
  ): IO[Either[JsonRpcErrorObject, CallToolResult]] = dispatchTuple[AllCommands](cmd)
