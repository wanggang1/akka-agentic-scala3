package com.gwgs.akkaagentic.streaming.probe

import akka.javasdk.client.ComponentClient
import com.gwgs.akkaagentic.streaming.application.StreamingChatAgent

/** Q-B attempt 1 + 2: the documented form, written in Scala. */
class AttemptSamLambda(componentClient: ComponentClient):

  def explicitLambda(sessionId: String, message: String) =
    componentClient
      .forAgent()
      .inSession(sessionId)
      .tokenStream[StreamingChatAgent, String]((a, m) => a.stream(m))
      .source(message)

  def placeholderLambda(sessionId: String, message: String) =
    componentClient
      .forAgent()
      .inSession(sessionId)
      .tokenStream[StreamingChatAgent, String](_.stream(_))
      .source(message)
