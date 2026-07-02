package com.gwgs.akkaagentic.api

import akka.http.javadsl.model.HttpResponse
import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.http.{HttpEndpoint, Post}
import akka.javasdk.client.ComponentClient
import akka.javasdk.http.HttpResponses
import com.fasterxml.jackson.annotation.{JsonCreator, JsonIgnoreProperties, JsonProperty}
import com.gwgs.akkaagentic.application.GreetingAgent
import com.gwgs.akkaagentic.domain.GreetingRequest

import java.util.UUID

object GreetingEndpoint:

  /** Inbound wire type. Unknown JSON properties are ignored so extra/unexpected
    * fields are accepted (spec.md → Edge Case Handling). Explicit Jackson
    * annotations keep round-tripping deterministic (research R3).
    */
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case class GreetRequest @JsonCreator() (
      @JsonProperty("user") user: String,
      @JsonProperty("text") text: String
  )

  /** Outbound wire type — never expose domain/application types (Constitution II). */
  final case class GreetReply @JsonCreator() (
      @JsonProperty("greeting") greeting: String
  )

@HttpEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class GreetingEndpoint(componentClient: ComponentClient):
  import GreetingEndpoint.*

  /** Compose a personalized greeting.
    *
    * Validates via the domain model first; invalid input returns `400` without
    * invoking the model. On success, calls [[GreetingAgent]] with a fresh session
    * id (stateless, FR-007) and wraps the reply in [[GreetReply]].
    */
  @Post("/greet")
  def greet(request: GreetRequest): HttpResponse =
    // Boundary: Jackson fills absent JSON properties with `null` (Java semantics,
    // since the wire types use plain Java-style annotations). `Option(...)` maps
    // `null -> None` here so the domain only ever deals with `Option`, never `null`.
    // (A future move to a Scala-native JSON library would remove `null` at the source
    // and let the wire types carry `Option` directly, making this conversion redundant.)
    GreetingRequest(Option(request.user), Option(request.text)).validate match
      case Left(message) =>
        HttpResponses.badRequest(message)
      case Right(valid) =>
        val greeting = componentClient
          .forAgent()
          .inSession(UUID.randomUUID().toString)
          .dynamicCall[GreetingAgent.Request, String]("greeting-agent")
          .invoke(GreetingAgent.Request(valid.user, valid.text))
        HttpResponses.ok(GreetReply(greeting))
