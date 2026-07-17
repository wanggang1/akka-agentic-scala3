package com.gwgs.akkaagentic.chat.api

import akka.http.javadsl.model.HttpResponse
import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.http.{HttpEndpoint, Post}
import akka.javasdk.client.ComponentClient
import akka.javasdk.http.HttpResponses
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.gwgs.akkaagentic.chat.domain.ChatMessage

/** Synchronous HTTP surface for capability 4 (multi-turn chat). {@code POST /chat/&#123;sessionId&#125;}
  * sends one message on the conversation identified by {@code sessionId} and returns the assistant's
  * reply directly — no start-then-poll (contrast cap-2/cap-3). Reusing the same {@code sessionId}
  * across requests is the feature: the runtime's session memory replays earlier turns as context.
  * Separate from — and independent of — capabilities 1–3.
  */
object ChatEndpoint:

  /** Inbound body — idiomatic Scala (feature 003): annotation-free, `Option` field. Absent/null
    * `message` deserializes to `None` (rejected by validation, not a 500). Unknown props tolerated.
    */
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case class ChatRequest(message: Option[String])

  /** Outbound reply — API-owned. Echoes the path `sessionId` so the caller can correlate and continue
    * the same conversation (FR-010); `reply` is the assistant's answer for this turn.
    */
  final case class ChatReply(sessionId: String, reply: String)

@HttpEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class ChatEndpoint(componentClient: ComponentClient):
  import ChatEndpoint.*

  /** Send one message on `sessionId`. Validates first; a blank/absent message returns `400` without
    * engaging the assistant. Otherwise calls `ChatAgent` in the caller's session (so prior turns are
    * replayed as context) and returns `200` with the reply.
    */
  @Post("/chat/{sessionId}")
  def chat(sessionId: String, request: ChatRequest): HttpResponse =
    ChatMessage.validate(request.message) match
      case Left(message) =>
        HttpResponses.badRequest(message)
      case Right(valid) =>
        val reply = componentClient
          .forAgent()
          .inSession(sessionId) // stable, caller-supplied — this is what makes the conversation multi-turn
          .dynamicCall[String, String]("chat-agent") // Scala lambdas can't be method-refs; call by id (cap-1 finding)
          .invoke(valid.text)
        HttpResponses.ok(ChatReply(sessionId, reply))
