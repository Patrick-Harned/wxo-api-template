package org.ibm.models
import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import org.http4s.EntityEncoder
import org.http4s.EntityDecoder
import org.http4s.MediaType
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
import org.ibm.models._
import scala.collection.concurrent.TrieMap
import org.ibm.Routes.{jsoniterEntityDecoder, jsoniterEntityEncoder}
import org.pwharned.json.JsonDeserializer
// --- 1. JSON-RPC Model Definitions ---
// These case classes represent the structure of JSON-RPC 2.0 messages and MCP specific types.
import org.ibm.models.JsonMap.{given, _}
// Base for all JSON-RPC messages
trait JsonRpcMessage {
  def jsonrpc: Option[String] = Some("2.0")
}

// Generic JSON-RPC Request
case class JsonRpcRequest[A](
    id: Option[
      Int
    ], // ID is optional for notifications, but required for requests
    method: String,
    params: Option[A]
) extends JsonRpcMessage
object JsonRpcRequest {
  implicit def jsonRpcRequestCodec[A: JsonValueCodec]
      : JsonValueCodec[JsonRpcRequest[A]] = JsonCodecMaker.make

}

// Generic JSON-RPC Response
case class JsonRpcResponse[A](
    id: Option[Int],
    result: A
) extends JsonRpcMessage
object JsonRpcResponse {
  implicit def jsonRpcResponseCodec[A: JsonValueCodec]
      : JsonValueCodec[JsonRpcResponse[A]] = JsonCodecMaker.make
  given JsonValueCodec[JsonRpcResponse[String]] = JsonCodecMaker.make
}

// Generic JSON-RPC Error Response
case class JsonRpcError(
    id: Option[Int] = None,
    error: JsonRpcErrorObject
) extends JsonRpcMessage
object JsonRpcError {
  implicit val jsonRpcErrorCodec: JsonValueCodec[JsonRpcError] =
    JsonCodecMaker.make
}

case class JsonRpcErrorObject(
    code: Int,
    message: String,
    data: Option[String] = None
)
object JsonRpcErrorObject {
  implicit val jsonRpcErrorObjectCodec: JsonValueCodec[JsonRpcErrorObject] =
    JsonCodecMaker.make
}

// Generic JSON-RPC Notification
case class JsonRpcNotification[A](
    method: String,
    params: A
) extends JsonRpcMessage
object JsonRpcNotification {
  implicit def jsonRpcNotificationCodec[A: JsonValueCodec]
      : JsonValueCodec[JsonRpcNotification[A]] = JsonCodecMaker.make
}

// --- MCP Specific Models ---

// Initialize Request
case class ClientInfo(
    name: String,
    version: String,
    title: Option[String] = None
)
object ClientInfo {
  implicit val clientInfoCodec: JsonValueCodec[ClientInfo] = JsonCodecMaker.make
}

case class Capabilities(
    roots: Option[Map[String, Boolean]] = None,
    sampling: Option[Map[String, Any]] = None,
    elicitation: Option[Map[String, Any]] = None,
    tasks: Option[Map[String, Any]] = None,
    tools: Option[Map[String, Any]] = None,
    logging: Option[Map[String, Any]] = None
)
object Capabilities {
  implicit val capabilitiesCodec: JsonValueCodec[Capabilities] =
    JsonCodecMaker.make
}

case class InitializeParams(
    protocolVersion: String,
    capabilities: Capabilities,
    clientInfo: ClientInfo
)
object InitializeParams {
  implicit val initializeParamsCodec: JsonValueCodec[InitializeParams] =
    JsonCodecMaker.make
}

// Initialize Response
case class ServerInfo(
    name: String,
    version: String,
    title: Option[String] = None
)
object ServerInfo {
  implicit val serverInfoCodec: JsonValueCodec[ServerInfo] = JsonCodecMaker.make
}

case class InitializeResult(
    protocolVersion: String,
    capabilities: Capabilities, // Server capabilities
    serverInfo: ServerInfo,
    instructions: Option[String] = None
)
object InitializeResult {
  implicit val initializeResultCodec: JsonValueCodec[InitializeResult] =
    JsonCodecMaker.make
}

// Tool Call Request

// Tool Call Result (simplified for this example)
case class ContentBlock(
    `type`: String, // e.g., "text"
    text: String
)
object ContentBlock {
  implicit val contentBlockCodec: JsonValueCodec[ContentBlock] =
    JsonCodecMaker.make
}

case class StructuredContent(result: Any) // For "add" tool
object StructuredContent {
  implicit val structuredContentCodec: JsonValueCodec[StructuredContent] =
    JsonCodecMaker.make
}

case class CallToolResult(
    content: List[ContentBlock],
    structuredContent: Option[StructuredContent] = None,
    isError: Boolean = false
)
object CallToolResult {
  implicit val callToolResultCodec: JsonValueCodec[CallToolResult] =
    JsonCodecMaker.make
}

// --- 2. http4s Entity Decoders and Encoders for Jsoniter ---
object JsoniterEntityCodec {
  // Decodes an HTTP entity (body) into a Scala type `A` using Jsoniter

  // Encodes a Scala type `A` into an HTTP entity (body) using Jsoniter
  def jsoniterEncoder[F[_], A: JsonValueCodec]: EntityEncoder[F, A] =
    EntityEncoder
      .stringEncoder[F]
      .withContentType(`Content-Type`(MediaType.application.json, `UTF-8`))
      .contramap[A](writeToString[A](_))

  // Encodes a Scala type `A` into an SSE-like data: string
  def sseDataEncoder[F[_], A: JsonValueCodec]: EntityEncoder[F, A] =
    EntityEncoder
      .stringEncoder[F]
      .withContentType(`Content-Type`(MediaType.`text/event-stream`, `UTF-8`))
      .contramap[A](obj => s"data: ${writeToString[A](obj)}\n\n")
}

sealed trait McpErrorType {
  def toRpcErrorObject: JsonRpcErrorObject
  def asRcpError = {
    JsonRpcError(
      Some(toRpcErrorObject.code),
      toRpcErrorObject
    )

  }
}
object McpErrorType {
  // Standard JSON-RPC errors
  case object ParseError extends McpErrorType {
    override def toRpcErrorObject: JsonRpcErrorObject = JsonRpcErrorObject(
      -32700,
      "Parse error: Invalid JSON was received by the server."
    )
  }
  case class InvalidRequest(details: String) extends McpErrorType {
    override def toRpcErrorObject: JsonRpcErrorObject =
      JsonRpcErrorObject(-32600, s"Invalid Request: $details")
  }
  case class MethodNotFound(methodName: String) extends McpErrorType {
    override def toRpcErrorObject: JsonRpcErrorObject =
      JsonRpcErrorObject(-32601, s"Method not found: $methodName")
  }
  case class InvalidParams(details: String) extends McpErrorType {
    override def toRpcErrorObject: JsonRpcErrorObject =
      JsonRpcErrorObject(-32602, s"Invalid params: $details")
  }
  case class InternalError(details: String) extends McpErrorType {
    override def toRpcErrorObject: JsonRpcErrorObject =
      JsonRpcErrorObject(-32603, s"Internal error: $details")
  }
  // MCP specific errors
  case object NotAcceptable extends McpErrorType {
    override def toRpcErrorObject: JsonRpcErrorObject = JsonRpcErrorObject(
      -32600,
      "Not Acceptable: Client must accept both application/json and text/event-stream"
    )
  }
  case object MethodNotAllowed extends McpErrorType {
    override def toRpcErrorObject: JsonRpcErrorObject = JsonRpcErrorObject(
      -32600,
      "Method Not Allowed: GET is not supported for this endpoint in this configuration"
    )
  }
  case object MissingSessionId extends McpErrorType {
    override def toRpcErrorObject: JsonRpcErrorObject =
      JsonRpcErrorObject(-32600, "Bad Request: Missing MCP-Session-Id header")
  }
  case object InvalidSessionId extends McpErrorType {
    override def toRpcErrorObject: JsonRpcErrorObject =
      JsonRpcErrorObject(-32600, "Bad Request: Invalid session ID")
  }
  case object SessionIdWithInitialize extends McpErrorType {
    override def toRpcErrorObject: JsonRpcErrorObject = JsonRpcErrorObject(
      -32600,
      "Bad Request: Session ID must not be sent with initialize request"
    )
  }
  case object MissingProtocolVersion extends McpErrorType {
    override def toRpcErrorObject: JsonRpcErrorObject = JsonRpcErrorObject(
      -32600,
      "Bad Request: Missing MCP-Protocol-Version header"
    )
  }
  case class UnhandledError(message: String) extends McpErrorType {
    override def toRpcErrorObject: JsonRpcErrorObject =
      JsonRpcErrorObject(-32000, s"Internal server error: $message")
  }
}
