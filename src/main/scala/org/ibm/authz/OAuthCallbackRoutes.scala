package org.ibm.authz

import cats.effect.*
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

case class CallbackError(
    error: String,
    message: String
)

object CallbackError {
  implicit val codec: JsonValueCodec[CallbackError] = JsonCodecMaker.make
}

class OAuthCallbackRoutes(
    config: OIDCConfig,
    tokenService: TokenService
) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "callback" :? CodeQueryParamMatcher(
          code
        ) +& StateQueryParamMatcher(state) =>
      tokenService
        .exchangeCodeForToken(code)
        .flatMap { tokenResponse =>
          val redirectPath = state.getOrElse("/")
          val cookie       = ResponseCookie(
            name = config.tokenName,
            content = tokenResponse.access_token,
            maxAge = Some(tokenResponse.expires_in.getOrElse(3600).toLong),
            httpOnly = true,
            secure = false, // Set to true in production with HTTPS
            path = Some("/"),
            sameSite = Some(SameSite.Lax)
          )

          Ok(landingPageWithRedirect(redirectPath))
            .map(_.addCookie(cookie))
            .map(_.withContentType(`Content-Type`(MediaType.text.html)))
        }
        .handleErrorWith { ex =>
          // Clear any existing cookie and show error
          val cookie = ResponseCookie(
            name = config.tokenName,
            content = "",
            maxAge = Some(-1),
            path = Some("/")
          )

          IO.println(s"Authentication failed: ${ex.getMessage}") *>
            Ok(errorPage(ex.getMessage))
              .map(_.addCookie(cookie))
              .map(_.withContentType(`Content-Type`(MediaType.text.html)))
        }

    case GET -> Root / "callback" :? ErrorQueryParamMatcher(
          error
        ) +& ErrorDescriptionQueryParamMatcher(desc) =>
      // OAuth provider returned an error
      val message =
        s"Authentication failed: $error${desc.map(d => s" - $d").getOrElse("")}"
      Ok(errorPage(message))
        .map(_.withContentType(`Content-Type`(MediaType.text.html)))

    case GET -> Root / "logout" =>
      val cookie = ResponseCookie(
        name = config.tokenName,
        content = "",
        maxAge = Some(-1),
        path = Some("/")
      )
      Ok(logoutPageWithRedirect("/"))
        .map(_.addCookie(cookie))
        .map(_.withContentType(`Content-Type`(MediaType.text.html)))

    case GET -> Root / "api" / "userinfo" =>
      // Public endpoint to get current user info (if authenticated)
      Ok("""{"message": "Use authenticated endpoint"}""")
  }

  // Query parameter matchers
  object CodeQueryParamMatcher extends QueryParamDecoderMatcher[String]("code")
  object StateQueryParamMatcher
      extends OptionalQueryParamDecoderMatcher[String]("state")
  object ErrorQueryParamMatcher
      extends QueryParamDecoderMatcher[String]("error")
  object ErrorDescriptionQueryParamMatcher
      extends OptionalQueryParamDecoderMatcher[String]("error_description")

  private def landingPageWithRedirect(path: String): String = s"""
    <!DOCTYPE html>
    <html lang="en">
    <head>
      <meta charset="UTF-8">
      <meta name="viewport" content="width=device-width, initial-scale=1.0">
      <meta http-equiv="refresh" content="0; url=$path">
      <title>Redirecting...</title>
      <style>
        body { font-family: Arial, sans-serif; text-align: center; padding: 50px; }
        .spinner { border: 4px solid #f3f3f3; border-top: 4px solid #3498db; 
                   border-radius: 50%; width: 40px; height: 40px; 
                   animation: spin 1s linear infinite; margin: 20px auto; }
        @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
      </style>
    </head>
    <body>
      <div class="spinner"></div>
      <p>Authentication successful. Redirecting to <a href="$path">$path</a>...</p>
      <script>window.location.href = "$path";</script>
    </body>
    </html>
  """

  private def logoutPageWithRedirect(path: String): String = s"""
    <!DOCTYPE html>
    <html lang="en">
    <head>
      <meta charset="UTF-8">
      <meta name="viewport" content="width=device-width, initial-scale=1.0">
      <meta http-equiv="refresh" content="2; url=$path">
      <title>Logged Out</title>
      <style>
        body { font-family: Arial, sans-serif; text-align: center; padding: 50px; }
        .message { color: #27ae60; font-size: 18px; }
      </style>
    </head>
    <body>
      <div class="message">
        <h2>✓ You have been logged out</h2>
        <p>Redirecting to <a href="$path">home</a> in 2 seconds...</p>
      </div>
      <script>setTimeout(function() { window.location.href = "$path"; }, 2000);</script>
    </body>
    </html>
  """

  private def errorPage(errorMessage: String): String = s"""
    <!DOCTYPE html>
    <html lang="en">
    <head>
      <meta charset="UTF-8">
      <meta name="viewport" content="width=device-width, initial-scale=1.0">
      <title>Authentication Error</title>
      <style>
        body { font-family: Arial, sans-serif; text-align: center; padding: 50px; }
        .error { color: #e74c3c; }
        .button { 
          display: inline-block; margin-top: 20px; padding: 10px 20px; 
          background-color: #3498db; color: white; text-decoration: none; 
          border-radius: 4px; 
        }
        .button:hover { background-color: #2980b9; }
      </style>
    </head>
    <body>
      <div class="error">
        <h2>❌ Authentication Error</h2>
        <p>${escapeHtml(errorMessage)}</p>
        <a href="/" class="button">Return Home</a>
      </div>
    </body>
    </html>
  """

  private def escapeHtml(s: String): String =
    s.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#x27;")
}
