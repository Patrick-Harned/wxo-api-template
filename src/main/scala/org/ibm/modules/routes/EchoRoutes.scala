package org.ibm.modules.routes
import cats.effect.kernel.Async
import cats.implicits.*
import org.http4s.HttpRoutes
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.*
import org.ibm.config.OIDCConfig
import org.ibm.domain.TokenResponse
import org.ibm.domain.ApiError
import org.ibm.services.TokenService
import org.ibm.TapirJsonCodec.{given, _}
import org.ibm.domain.AuthenticatedUser
import org.ibm.domain.UserInfo
import sttp.tapir.server.ServerEndpoint
trait EchoRoutes[F[_]]:
  def routes: HttpRoutes[F]
object EchoRoutes:
  def live[F[_]: Async](
      tokenService: TokenService[F],
      config: OIDCConfig
  ): EchoRoutes[F] =
    new EchoRoutes[F]:
      def routes: HttpRoutes[F] = Http4sServerInterpreter[F]().toRoutes(echoEndpoint)
      private val echoEndpoint: ServerEndpoint[Any, F] =
        endpoint.post
          .in("oauth" / "echo")
          .in(formBody[Map[String, String]])
          .out(jsonBody[TokenResponse])
          .errorOut(jsonBody[ApiError])
          .serverLogic { formData =>
            for {
              assertion <- Async[F].pure(
                formData
                  .get("assertion")
                  .toRight(
                    ApiError(
                      s"Missing assertion in request. Requested fields: ${formData.toList
                          .map { case (k, v) => s"$k:$v" }
                          .mkString(",")}"
                    )
                  )
              )
              _ <- Async[F].delay(logFormData(formData))
              result <- assertion match {
                case Left(err) => Async[F].pure(Left(err))
                case Right(assertionValue) =>
                  tokenService.validateToken(assertionValue).map { response =>
                    val remainingTime =
                      response
                        .flatMap(x =>
                          x.introspectionResponse
                            .flatMap(
                              _.exp.map(ex => ex.toInt - (System.currentTimeMillis() / 1000).toInt)
                            )
                        )
                        .getOrElse(600)
                    val maxExpiration =
                      sys.env
                        .get("TOKEN_EXPIRY")
                        .flatMap(_.toIntOption)
                        .getOrElse(300)
                    Right(
                      TokenResponse(
                        access_token = assertionValue,
                        token_type = "Bearer",
                        expires_in = Some(Math.min(remainingTime, maxExpiration)),
                        refresh_token = None,
                        scope = None
                      )
                    )
                  }
              }
            } yield result
          }
      private def logFormData(formData: Map[String, String]): Unit =
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
            key != "client_id" && key != "client_secret" &&
            (value == "null" || value.isEmpty)
          }
          .map { case (key, value) => s"$key:'$value'" }
          .mkString(", ")
        println(
          s"""Submitted form data analysis:
             |  $clientIdInfo
             |  $clientSecretInfo
             |  ${
              if (otherProblematicFields.nonEmpty)
                s"Other problematic fields: $otherProblematicFields"
              else ""
            }
             |""".stripMargin
        )
