package org.ibm

import cats.effect._
import munit.CatsEffectSuite
import org.http4s._
import org.http4s.client._
import org.http4s.implicits._
import org.http4s.dsl.io._
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.server.ServerEndpoint

class RouteTests extends CatsEffectSuite {

  // Build HttpApp from all GET endpoints
  def httpAppFrom(eps: List[ServerEndpoint[Any, IO]]): HttpApp[IO] =
    Http4sServerInterpreter[IO]().toRoutes(eps).orNotFound

  val httpApp: HttpApp[IO] = httpAppFrom(SwaggerDocumentation.allEndpoints)
  val client: Client[IO]   = Client.fromHttpApp(httpApp)

  // Helper to run GET and assert Status.Ok
  private def assertGetOk(path: Uri): IO[Unit] = {
    val req = Request[IO](Method.GET, path)
    client.run(req).use { resp =>
      IO(assertEquals(resp.status, Status.Ok))
    }
  }

  // All GET route tests
}
