package org.ibm.authz

import com.github.plokhotnyuk.jsoniter_scala.core.*

case class OIDCConfig(
    clientId: String,
    clientSecret: String,
    authorizationEndpoint: String,
    tokenEndpoint: String,
    userInfoEndpoint: String,
    introspectionEndpoint: String,
    redirectUri: String,
    scope: String = "openid profile email",
    tokenName: String = "auth_token"
)

object OIDCConfig {
  def fromEnv: OIDCConfig = OIDCConfig(
    clientId = sys.env.getOrElse("OIDC_CLIENT_ID", ""),
    clientSecret = sys.env.getOrElse("OIDC_CLIENT_SECRET", ""),
    authorizationEndpoint = sys.env.getOrElse("OIDC_AUTH_ENDPOINT", ""),
    tokenEndpoint = sys.env.getOrElse("OIDC_TOKEN_ENDPOINT", ""),
    userInfoEndpoint = sys.env.getOrElse("OIDC_USERINFO_ENDPOINT", ""),
    introspectionEndpoint =
      sys.env.getOrElse("OIDC_INTROSPECTION_ENDPOINT", ""),
    redirectUri =
      sys.env.getOrElse("OIDC_REDIRECT_URI", "http://localhost:8080/callback"),
    tokenName = sys.env.getOrElse("OIDC_TOKEN_NAME", "auth_token")
  )
}
