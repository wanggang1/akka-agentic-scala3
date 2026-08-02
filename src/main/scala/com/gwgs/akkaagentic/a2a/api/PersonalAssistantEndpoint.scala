package com.gwgs.akkaagentic.a2a.api

import akka.http.javadsl.model.HttpResponse
import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.http.{HttpEndpoint, Post}
import akka.javasdk.client.ComponentClient
import akka.javasdk.http.HttpResponses
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.gwgs.akkaagentic.a2a.application.PersonalAssistantAgent
import com.gwgs.akkaagentic.a2a.domain.AssistantRequest

/** Synchronous HTTP surface for capability 6 (agent-to-agent delegation).
  *
  * `POST /request/{username}` sends one message to `{username}`'s personal assistant and returns the
  * reply directly — no start-then-poll (like cap-4, unlike cap-2/cap-3). The assistant may, on its own,
  * manage `{username}`'s to-dos or delegate to another user's assistant before replying. `username`
  * scopes both the chat history (session memory) and the to-do list, so distinct usernames are distinct,
  * isolated assistants. Capabilities 1–5 are untouched.
  */
object PersonalAssistantEndpoint:

  /** Inbound body — idiomatic Scala (feature 003): annotation-free, `Option` field. Absent/null
    * `message` deserializes to `None` (rejected by validation, not a 500). Unknown props tolerated.
    * Note there is NO `delegated` field here: it is an internal, agent-to-agent concern (research R4),
    * defaulted behind this boundary and set only by `ForwardTool` — never by the API caller.
    */
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case class RequestBody(message: Option[String])

  /** Outbound reply — API-owned. Echoes the path `username` (whose assistant replied) and the answer. */
  final case class Reply(username: String, reply: String)

@HttpEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class PersonalAssistantEndpoint(componentClient: ComponentClient):
  import PersonalAssistantEndpoint.*

  /** Send one message to `username`'s assistant. Validates first (non-blank username + message); a
    * blank/absent value returns `400` without engaging the assistant. Otherwise calls
    * `PersonalAssistantAgent` in the caller's session (= username, so prior turns replay as context and
    * to-dos resolve to this user) and returns `200` with the reply.
    */
  @Post("/request/{username}")
  def request(username: String, body: RequestBody): HttpResponse =
    AssistantRequest.validate(username, body.message) match
      case Left(message) =>
        HttpResponses.badRequest(message)
      case Right(valid) =>
        val reply = componentClient
          .forAgent()
          .inSession(valid.username) // session id = username: multi-turn history + per-user to-dos
          .dynamicCall[PersonalAssistantAgent.Request, String]("personal-assistant-agent") // call by id (cap-1 finding)
          .invoke(PersonalAssistantAgent.Request(valid.username, valid.message)) // delegated defaults false
        HttpResponses.ok(Reply(valid.username, reply))
