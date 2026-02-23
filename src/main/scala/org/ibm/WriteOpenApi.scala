package org.ibm

import cats.effect.*
import cats.implicits.*
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.charset.StandardCharsets

object WriteOpenApi extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {

    for {
      // Ensure certificate exists if TLS is enabled

      // Write OpenAPI docs
      _ <- IO {
        val docs = SwaggerDocumentation.docsAsJson
        Files.write(
          Paths.get("openapi.yaml"),
          docs.getBytes(StandardCharsets.UTF_8)
        )
        println("OpenAPI documentation written to openapi.yaml")
      }

      // Log startup info
    } yield ExitCode.Success
  }
}
