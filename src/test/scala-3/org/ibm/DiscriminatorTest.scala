package org.ibm

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

object JsoniterDiscriminatorExampleStep3 extends App {

  // --- Redeclared Models (mimicking org.ibm.models and org.ibm.mcp) ---

  // Assuming 'Optional' is an alias for scala.Option
  type Optional[A] = scala.Option[A]

  // Mimicking a specific argument type, e.g., InvoiceSpendCategoriesAndPO
  case class InvoiceSpendCategoriesAndPO(INVOICE_NUMBER: String)
  object InvoiceSpendCategoriesAndPO:
    implicit val codec: JsonValueCodec[InvoiceSpendCategoriesAndPO] =
      JsonCodecMaker.make

  // Mimicking the sealed trait ToolCallParams
  sealed trait ToolCallParams:
    val name: String // Discriminator field

  // Mimicking a specific ToolCallParams subtype, e.g., InvoiceSpendCategoriesAndPOTool
  case class InvoiceSpendCategoriesAndPOTool(
      arguments: Optional[InvoiceSpendCategoriesAndPO]
  ) extends ToolCallParams:
    val name: String =
      "INVOICE_SPEND_CATEGORIES_AND_PO" // Matches the JSON discriminator value

  // Companion object for ToolCallParams to define the discriminator codec
  object ToolCallParams:
    implicit val classUseOneTypeCodec: JsonValueCodec[ToolCallParams] =
      JsonCodecMaker.make(
        CodecMakerConfig
          .withDiscriminatorFieldName(
            Some("name")
          ) // Specify "name" as the discriminator
      )

  // --- Test Execution ---

  // The JSON snippet for the 'params' field from your Python test
  val toolCallJson = """
    {
      "name": "InvoiceSpendCategoriesAndPOTool",
      "arguments": {
        "INVOICE_NUMBER": "INV-2024-00123"
      }
    }
  """

  println("Attempting to deserialize ToolCallParams...")
  try {
    val deserializedToolCall: ToolCallParams =
      readFromString[ToolCallParams](toolCallJson)

    println(s"SUCCESS: Deserialized ToolCallParams: $deserializedToolCall")
    deserializedToolCall match {
      case tool: InvoiceSpendCategoriesAndPOTool =>
        println(s"  - Recognized as InvoiceSpendCategoriesAndPOTool.")
        println(s"  - Arguments: ${tool.arguments}")
      case other =>
        println(
          s"  - Deserialized to unexpected type: ${other.getClass.getName}"
        )
    }

  } catch {
    case e: JsonReaderException =>
      println(
        s"FAILURE: JsonReaderException during ToolCallParams deserialization: ${e.getMessage}"
      )
      e.printStackTrace()
    case e: Exception =>
      println(
        s"FAILURE: Unexpected error during ToolCallParams deserialization: ${e.getMessage}"
      )
      e.printStackTrace()
  }
}
