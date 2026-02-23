package org.ibm
import sttp.tapir._
import cats.effect._
import sttp.tapir.server.ServerEndpoint
import org.ibm.TelCodec.jsonBody
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.server.http4s.Http4sServerOptions
import sttp.tapir.server.interceptor.log.DefaultServerLog
import sttp.tapir.server.interceptor.RequestInterceptor
import org.ibm.authz.OIDCConfig
import org.ibm.authz.TokenService
import org.ibm.models.ApiError

lazy val echoToken: ServerEndpoint[Any, IO] =
  endpoint.post
    .in("oauth" / "echo")
    .in(formBody[Map[String, String]])
    .out(jsonBody[TokenResponse])
    .errorOut(jsonBody[ApiError])
    .serverLogic { formData =>
      // Everything runs inside IO
      for {
        assertion <- IO.pure(
          formData
            .get("assertion")
            .toRight(
              ApiError(
                s"Missing assertion in request. Requested fields: ${formData.toList.map { case (k, v) => s"$k:$v" }.mkString(",")}"
              )
            )
        )
        _ <- IO.println {
          val clientIdInfo = formData
            .get("client_id")
            .map { value =>
              val redacted =
                if (value.length > 6) s"${value.substring(0, 6)}...[REDACTED]"
                else "[REDACTED]"
              val problematic =
                if (value == "null" || value.isEmpty)
                  s", WARNING: problematic value ('$value')"
                else ""
              s"client_id: YES, value: '$redacted'$problematic"
            }
            .getOrElse("client_id: NO (MISSING KEY)")

          val clientSecretInfo = formData
            .get("client_secret")
            .map { value =>
              val redacted =
                if (value.length > 6) s"${value.substring(0, 6)}...[REDACTED]"
                else "[REDACTED]"
              val problematic =
                if (value == "null" || value.isEmpty)
                  s", WARNING: problematic value ('$value')"
                else ""
              s"client_secret: YES, value: '$redacted'$problematic"
            }
            .getOrElse("client_secret: NO (MISSING KEY)")

          val otherProblematicFields = formData.toList
            .filter { case (key, value) =>
              key != "client_id" && key != "client_secret" && (value == "null" || value.isEmpty)
            }
            .map { case (key, value) => s"$key:'$value'" }
            .mkString(", ")

          val otherProblematicString = if (otherProblematicFields.nonEmpty) {
            s"\n  Other problematic fields (empty or 'null' string): $otherProblematicFields"
          } else ""

          s"Submitted form data analysis:\n  $clientIdInfo\n  $clientSecretInfo$otherProblematicString"
        }
        result <- assertion match {
          case Left(err) => IO.pure(Left(err))

          case Right(assertionValue) =>
            val oidc = OIDCConfig.fromEnv
            val ts   = TokenService(oidc)

            // Continue inside IO
            for {
              /*
              clientId <- IO.pure(
                formData
                  .get("client_id")
                  .toRight(ApiError("missing client_id in form"))
              )

              clientSecret <- IO.pure(
                formData
                  .get("client_secret")
                  .toRight(ApiError("missing client_secret in form"))
              )

              authCheck <- IO.pure(
                for {
                  id  <- clientId
                  sec <- clientSecret
                  _   <- Either.cond(
                    id == oidc.clientId && sec == oidc.clientSecret,
                    (),
                    ApiError("Unauthorized")
                  )
                } yield ()
              )
               */
              finalResult <- {

                ts.introspectToken(assertionValue).attempt.map {
                  case Left(e)  => Left(ApiError(e.getMessage))
                  case Right(x) =>
                    val now           = System.currentTimeMillis() / 1000
                    val remainingTime = x.exp.map(exp => (exp - now).toInt)
                    val maxExpiration =
                      sys.env
                        .get("TOKEN_EXPIRY")
                        .flatMap(_.toIntOption)
                        .getOrElse(300)
                    val cappedExpiration =
                      remainingTime.map(t => Math.min(t, maxExpiration))

                    Right(
                      TokenResponse(
                        access_token = assertionValue,
                        token_type = "Bearer",
                        expires_in = cappedExpiration
                      )
                    )
                }
              }
            } yield finalResult
        }
      } yield result
    }

lazy val echoTokenRoute =
  Http4sServerInterpreter[IO](serverOptions).toRoutes(echoToken)
case class TokenResponse(
    access_token: String,
    token_type: String,
    expires_in: Option[Int] = None
)
object TokenResponse:
  given Schema[TokenResponse] = Schema.derived
