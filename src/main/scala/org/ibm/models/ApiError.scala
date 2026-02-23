package org.ibm.models

import sttp.tapir.Schema
case class ApiError(message: String)
object ApiError {
  given Schema[ApiError] = Schema.derived
}
