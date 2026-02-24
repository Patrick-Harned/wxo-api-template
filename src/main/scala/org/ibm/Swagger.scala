package org.ibm
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.swagger.SwaggerUI
import org.http4s.HttpRoutes
import cats.effect.IO
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.apispec.openapi.Info
import org.ibm.Routes._
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.apispec.openapi.circe.yaml.*
import sttp.apispec.openapi._
import sttp.apispec.SecurityScheme
import sttp.tapir.TapirAuth.oauth2
import scala.collection.mutable.ListMap
import sttp.tapir.EndpointInput.AuthType.OAuth2

object SwaggerDocumentation {
  val allEndpoints = List(
    health,
    getUsers,
    openAccont,
    accountInfo
  )

  val info = Info(
    title = "TelAssets API",
    version = "1.0.0"
  )
  val oauth2Scheme = OAuth2(
    authorizationUrl = Some(
      "https://preprod.login.w3.ibm.com/v1.0/endpoint/default/authorize"
    ),
    tokenUrl = Some(
      "https://preprod.login.w3.ibm.com/v1.0/endpoint/default/token"
    ),
    refreshUrl = None,
    scopes = scala.collection.immutable.ListMap(
      "openid" -> "Grants access to resources"
    )
  )
  val oauth2Auth = oauth2
    .authorizationCodeFlow(
      authorizationUrl = "https://preprod.login.w3.ibm.com/v1.0/endpoint/default/authorize",
      tokenUrl = "https://preprod.login.w3.ibm.com/v1.0/endpoint/default/token",
      scopes = scala.collection.immutable
        .ListMap("openid" -> "Grants access to resources")
    )
    .description(
      "This API uses OAuth 2.0 Authorization Code Flow to secure endpoints."
    )
    .securitySchemeName(
      "oauth2"
    ) // this becom

  val swaggerEndpoints = SwaggerInterpreter().fromEndpoints[IO](
    allEndpoints.map(x => x.endpoint),
    "EPM Tool API",
    "1.0"
  )

  val docsAsJson: String =
    OpenAPIDocsInterpreter()
      .toOpenAPI(allEndpoints.map(x => x.endpoint), "EPM Tool", "1.0")
      .servers(
        List(
          Server("https://epm-tool.com").description("Production server")
        )
      )
      .toYaml

}
