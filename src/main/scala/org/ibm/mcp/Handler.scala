package org.ibm.mcp
import cats.effect._
import org.ibm.models.CallToolResult
trait Handler[A]:
  def handle(a: A): IO[CallToolResult]
object Handler:
  def apply[A](using h: Handler[A]) = h
trait HasArguments[T <: ToolCallParams, Args] {
  def getArguments(toolCall: T): Args
}
