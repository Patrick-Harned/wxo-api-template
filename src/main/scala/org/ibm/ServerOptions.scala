package org.ibm
import sttp.tapir._
import cats.effect._
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.server.http4s.Http4sServerOptions
import sttp.tapir.server.interceptor.log.DefaultServerLog
import sttp.tapir.server.interceptor.RequestInterceptor
val serverOptions = Http4sServerOptions
  .customiseInterceptors[IO]
  .serverLog(
    DefaultServerLog[IO](
      doLogWhenReceived = msg => IO.println(s"[REQUEST] $msg"),
      doLogWhenHandled = (msg, error) => IO.println(s"[HANDLED] $msg, error: $error"),
      doLogAllDecodeFailures = (msg, error) => IO.println(s"[DECODE FAIL] $msg, error: $error"),
      doLogExceptions = (msg, ex) => IO.println(s"[EXCEPTION] $msg: ${ex.getMessage}"),
      noLog = IO.unit
    )
  )
  .prependInterceptor(RequestInterceptor.transformServerRequest { request =>
    IO.println(s"[RAW REQUEST]") *>
      IO.println(s"  Method: ${request.method}") *>
      IO.println(s"  URI: ${request.uri}") *>
      IO.println(request.cookies) *>
      IO.println(request.headers) *>
      IO.pure(request)
  })
  .options
