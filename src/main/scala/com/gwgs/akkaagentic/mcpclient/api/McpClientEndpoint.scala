package com.gwgs.akkaagentic.mcpclient.api

import java.util.UUID

import akka.http.javadsl.model.HttpResponse
import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.http.{HttpEndpoint, Post}
import akka.javasdk.client.ComponentClient
import akka.javasdk.http.HttpResponses
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.gwgs.akkaagentic.docs.domain.AskQuestion

/** Synchronous HTTP surface for capability 10 (MCP-client / agentic RAG). `POST /grounded-ask`
  * validates the question, then hands it to [[com.gwgs.akkaagentic.mcpclient.application.McpClientAgent]],
  * whose model grounds the answer by calling the remote `retrieve` MCP tool (this service's own cap-9
  * `/mcp` server) at its own discretion. Returns the answer directly — no start-then-poll (like cap-4/
  * cap-8).
  *
  * Contrast with cap-8's `DocsEndpoint`: there the endpoint retrieves top-k passages *itself* before
  * the model runs (pre-retrieval) and can therefore cite ground-truth sources. Here retrieval is a
  * **tool the model owns**, so the endpoint no longer knows which passages grounded the answer — hence
  * **no `citedSources`** (a deliberate cap-8-fork tradeoff; specs/012 Assumptions). The endpoint stays
  * thin: validate → call agent → wrap the reply.
  */
object McpClientEndpoint:

  /** Inbound body — idiomatic Scala (feature 003): annotation-free, `Option` field. Absent/null
    * `question` deserializes to `None` (rejected by validation, not a 500). Unknown props tolerated. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case class AskRequest(question: Option[String])

  /** Outbound reply — API-owned. Just the answer text (grounded, or an honest decline). No citations:
    * the model owns retrieval here, so the endpoint can't compute ground-truth sources (unlike cap-8). */
  final case class AskReply(answer: String)

@HttpEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class McpClientEndpoint(componentClient: ComponentClient):
  import McpClientEndpoint.*

  /** Answer a question, grounding via the agent's remote `retrieve` MCP tool. Validates first — a
    * blank/absent question returns `400` with no model or tool call (FR-005). Otherwise the agent is
    * invoked on a fresh session (each question is independent) and its bare-`String` reply is wrapped.
    */
  @Post("/grounded-ask")
  def ask(request: AskRequest): HttpResponse =
    AskQuestion.validate(request.question) match
      case Left(message) =>
        HttpResponses.badRequest(message)
      case Right(valid) =>
        val answer = componentClient
          .forAgent()
          .inSession(UUID.randomUUID().toString) // each question independent; fresh session
          .dynamicCall[String, String]("mcp-client-agent") // Scala can't use Java method refs (§2)
          .invoke(valid.question)
        HttpResponses.ok(AskReply(answer))
