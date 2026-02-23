package org.ibm.mcp

import cats.effect.IO
import org.ibm.models._
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec

// Assuming these are defined in org.ibm.models
// import JsonRpcResponse
// import CallToolResult
// import ContentBlock
// import StructuredContent
// import JsonRpcError
// import JsonRpcErrorObject
// import McpErrorType.MethodNotFound

// Define the Tool trait
trait Tool[A] {
  def name: String
  def execute(
      arguments: A,
      id: Option[Int]
  ): IO[Either[JsonRpcError, JsonRpcResponse[CallToolResult]]]
}
