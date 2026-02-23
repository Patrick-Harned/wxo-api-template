package org.ibm.models

import com.github.plokhotnyuk.jsoniter_scala.core.*

case class WXOConfig(
    orchestrationId: String,
    hostUrl: String,
    agentId: String,
    agentEnvironmentId: String
)

object WXOConfig {
  def fromEnv: WXOConfig = WXOConfig(
    orchestrationId = sys.env.getOrElse(
      "WXO_ORCHESTRATION_ID",
      throw new RuntimeException("Missing WXO_ORCHESTRATION_ID")
    ),
    hostUrl = sys.env.getOrElse(
      "WXO_HOST_URL",
      throw new RuntimeException("Missing WXO_HOST_URL")
    ),
    agentId = sys.env.getOrElse(
      "WXO_AGENT_ID",
      throw new RuntimeException("Missing WXO_AGENT_ID")
    ),
    agentEnvironmentId = sys.env.getOrElse(
      "WXO_AGENT_ENVIRONMENT_ID",
      throw new RuntimeException("Missing WXO_AGENT_ENVIRONMENT_ID")
    )
  )
}
