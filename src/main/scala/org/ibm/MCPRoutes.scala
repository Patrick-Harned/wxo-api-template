package org.ibm
import cats.effect._
import cats.implicits._
import fs2._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.headers.`Content-Type`
import org.http4s.MediaType
import org.http4s.Charset.`UTF-8`
import org.http4s.util.CaseInsensitiveString
import java.util.UUID
import org.ibm.models._            // Import all models, including McpErrorType and its members
import org.ibm.models.McpErrorType // Import members of McpErrorType for direct access
import scala.collection.concurrent.TrieMap
import org.ibm.models.JsonMap.{given, _}
import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import java.nio.charset.StandardCharsets
import org.ibm.mcp.Dispatcher
import cats.Applicative
import org.ibm.mcp.ToolCallParams

object MCPRoutes {
  def sseJsoniterEntityEncoder[F[_], A: JsonValueCodec]: EntityEncoder[F, A] =
    EntityEncoder
      .stringEncoder[F]
      .withContentType(`Content-Type`(MediaType.`text/event-stream`, `UTF-8`))
      .contramap[A](obj => s"data: ${writeToString[A](obj)}\n\n")
  implicit def jsoniterEntityEncoder[A: JsonValueCodec]: EntityEncoder[IO, A] =
    EntityEncoder
      .byteArrayEncoder[IO]
      .contramap[A](x => writeToString(x).getBytes(StandardCharsets.UTF_8))

  implicit def jsoniterEntityDecoder[A <: Product](using
      deser: JsonValueCodec[A]
  ): EntityDecoder[IO, A] =
    EntityDecoder
      .byteArrayDecoder[IO]
      .map { x =>
        scala.util
          .Try {
            readFromString[A](new String(x))
          }
          .toEither
          .fold(
            x => throw new RuntimeException(s"Deserialization failed : ${x}"),
            { case x => x }
          )
      }

  val activeSessions =
    TrieMap.empty[String, Unit] // Value doesn't matter for this minimal example

  // --- 4. HTTP Routes Definition ---
  def mcpRoutes: HttpRoutes[IO] =
    HttpRoutes.of[IO] {

      // Handle all POST requests to the /mcp endpoint
      case req @ POST -> Root / "mcp" =>
        // Validate Accept header
        val acceptHeader = req.headers.get(CaseInsensitiveString("Accept"))
        val requiresAccept = acceptHeader
          .exists { h =>
            h.exists(x =>
              x.value.contains("application/json") && x.value.contains(
                "text/event-stream"
              )
            )
          }
        if (!requiresAccept) {
          BadRequest(
            McpErrorType.NotAcceptable.asRcpError
          )
        }
        else {
          // Extract MCP-Session-Id and MCP-Protocol-Version headers
          val sessionIdOpt =
            req.headers
              .get(CaseInsensitiveString("MCP-Session-Id"))
              .map(x => x.head.value)
          val protocolVersionOpt = req.headers
            .get(CaseInsensitiveString("MCP-Protocol-Version"))
            .map(_.head.value) match
            case Some(value) => Some(value)
            case None        => Some("2.0")

          // Parse the incoming JSON-RPC message as a generic request to inspect method/id
          req
            .as[JsonRpcRequest[JsonMap]]
            .flatMap { rpcRequest => // Codec is implicitly resolved from companion object
              rpcRequest.method match {

                case "initialize" =>
                  // Ensure no session ID is sent with initialize
                  if (sessionIdOpt.isDefined) {
                    BadRequest(
                      McpErrorType.SessionIdWithInitialize.asRcpError
                    )
                  }
                  else {
                    // Parse specific initialize params
                    req
                      .as[JsonRpcRequest[InitializeParams]]
                      .flatMap { initReq => // Codec is implicitly resolved
                        val newSessionId = UUID.randomUUID.toString
                        activeSessions.put(newSessionId, ())

                        val serverCapabilities = Capabilities(
                          tools = Some(Map("listChanged" -> true, "call" -> true)),
                          logging = Some(
                            Map.empty
                          ) // Declare logging capability as FastMCP does
                        )
                        val serverInfo =
                          ServerInfo(name = "ScalaMcpServer", version = "1.0.0")
                        val initializeResult = InitializeResult(
                          protocolVersion = initReq.params
                            .map(x => x.protocolVersion)
                            .getOrElse(
                              "2.0"
                            ), // Echo client's requested version
                          capabilities = serverCapabilities,
                          serverInfo = serverInfo
                        )
                        val rpcResponse = JsonRpcResponse(
                          id = initReq.id,
                          result = initializeResult
                        )

                        // Respond with MCP-Session-Id header and application/json content type
                        Ok(rpcResponse)
                          .map(
                            _.putHeaders(
                              Header.Raw(
                                CaseInsensitiveString("MCP-Session-Id"),
                                newSessionId
                              )
                            )
                          )
                          .map(
                            _.putHeaders(
                              Header.Raw(
                                CaseInsensitiveString("MCP-Protocol-Version"),
                                initReq.params
                                  .map(x => x.protocolVersion)
                                  .getOrElse("2.0")
                              )
                            )
                          )
                      }
                  }

                case "notifications/initialized" =>
                  // Validate session ID
                  sessionIdOpt match {
                    case Some(sessionId) if activeSessions.contains(sessionId) =>
                      Accepted()
                    case _ =>
                      BadRequest(
                        McpErrorType.MissingSessionId.asRcpError
                      )
                  }

                case "tools/call" =>
                  // Validate session ID and protocol version
                  (sessionIdOpt, protocolVersionOpt) match {

                    case (Some(sessionId), Some(protocolVersion))
                        if activeSessions.contains(sessionId) =>
                      req
                        .as[JsonRpcRequest[ToolCallParams]]
                        .flatMap { toolReq => // Codec is implicitly resolved
                          toolReq.params match {
                            case Some(tool) =>
                              // ToolDispatcher.dispatch returns IO[Either[JsonRpcErrorObject, CallToolResult]]
                              Dispatcher.dispatch(tool).flatMap {
                                case Right(callToolResult) =>
                                  val rpcResponse = JsonRpcResponse(
                                    id = toolReq.id,
                                    result = callToolResult
                                  )
                                  Ok(rpcResponse)(using
                                    implicitly[Applicative[IO]],
                                    sseJsoniterEntityEncoder[
                                      IO,
                                      JsonRpcResponse[CallToolResult]
                                    ]
                                  )
                                case Left(errorObject) =>
                                  val rpcError = JsonRpcError(
                                    id = toolReq.id,    // Use the original request ID for the error
                                    error = errorObject // Use the errorObject directly
                                  )
                                  Ok(rpcError) // Return 200 OK with an error object in the body for JSON-RPC errors
                              }
                            case None => // If 'params' field was missing in the JSON-RPC request
                              BadRequest(
                                McpErrorType
                                  .InvalidParams("Missing tool call parameters")
                                  .asRcpError
                              )
                          }
                        }
                    case (None, _) =>
                      BadRequest(
                        McpErrorType.MissingSessionId.asRcpError
                      )
                    case (_, None) =>
                      BadRequest(
                        McpErrorType.MissingProtocolVersion.asRcpError
                      )
                    case _ =>
                      BadRequest(
                        McpErrorType.InvalidSessionId.asRcpError
                      )
                  }

                case unknownMethod =>
                  BadRequest(
                    McpErrorType
                      .MethodNotFound(unknownMethod)
                      .asRcpError
                  )
              }
            }
            .handleErrorWith {
              case e: MalformedMessageBodyFailure =>
                BadRequest(
                  McpErrorType.ParseError.asRcpError
                )
              case e =>
                IO.println(s"Unhandled error in MCP POST endpoint: $e") >>
                  InternalServerError(
                    McpErrorType.UnhandledError(e.getMessage).asRcpError
                  )
            }
        }

      // Handle GET requests to /mcp by returning 405 Method Not Allowed
      case GET -> Root / "mcp" =>
        BadRequest(McpErrorType.MethodNotAllowed.asRcpError)
    }

}
