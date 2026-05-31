package org.ibm.config

import scala.util.Failure
import scala.util.Success

case class AppConfig(
    oidcConfig: OIDCConfig,
    tlsConfig: TLSConfig,
    jwtConfig: JwtConfig
)

object AppConfig:

  def fromEnv: AppConfig =
    JwtConfig.fromEnv match
      case Failure(exception) => throw exception
      case Success(value)     => new AppConfig(OIDCConfig.fromEnv, TLSConfig.fromEnv, value)
