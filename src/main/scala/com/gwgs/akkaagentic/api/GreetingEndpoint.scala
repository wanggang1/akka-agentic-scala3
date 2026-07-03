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

  /** Inbound wire type — idiomatic Scala (feature 003): a plain, annotation-free case class
    * with `Option` fields. The Scala Jackson module (registered at startup by the service
    * `Bootstrap`) maps absent-or-null JSON to `None` and present values to `Some`, so no
    * `@JsonCreator`/`@JsonProperty` and no boundary `null -> None` conversion are needed.
    *
    * `@JsonIgnoreProperties(ignoreUnknown = true)` is kept: tolerating unknown properties is a
    * contract behavior (spec.md → Edge Case Handling), independent of Scala/Java shaping.
    * `timezone` stays optional; an absent/blank/invalid zone safely falls back to UTC in the
    * domain (`TimeOfDay`), so it is never a validation error.
    */
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case class GreetRequest(
      user: Option[String],
      text: Option[String],
      timezone: Option[String]
  )

  /** Outbound wire type — never expose domain/application types (Constitution II).
    * Mirrors the agent's structured [[GreetingAgent.Result]] shape, but stays an
    * API-owned type so the wire contract is independent of the application layer.
    */
  final case class GreetReply @JsonCreator() (
      @JsonProperty("greeting") greeting: String,
      @JsonProperty("tone") tone: String,
      @JsonProperty("timeOfDay") timeOfDay: String
  )

  /** Map the application result to the API wire type (API isolation). */
  private def toApi(result: GreetingAgent.Result): GreetReply =
    GreetReply(result.greeting, result.tone, result.timeOfDay)

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
    // The Scala Jackson module already deserialized absent/null JSON to `None`, so the
    // request's `Option` fields flow straight into the domain — no boundary conversion.
    GreetingRequest(request.user, request.text).validate match
      case Left(message) =>
        HttpResponses.badRequest(message)
      case Right(valid) =>
        val result = componentClient
          .forAgent()
          .inSession(UUID.randomUUID().toString)
          // Interim glue: GreetingAgent.Request.timezone is still a nullable String until
          // US2/T008 converts it to Option; `orNull` bridges until then.
          .dynamicCall[GreetingAgent.Request, GreetingAgent.Result]("greeting-agent")
          .invoke(GreetingAgent.Request(valid.user, valid.text, request.timezone.orNull))
        HttpResponses.ok(toApi(result))
