package com.gwgs.akkaagentic.docs.api

import java.nio.charset.StandardCharsets
import java.util.UUID

import scala.jdk.CollectionConverters.*

import akka.http.javadsl.model.{ContentTypes, HttpResponse, StatusCodes}
import akka.javasdk.JsonSupport
import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.http.{HttpEndpoint, Post}
import akka.javasdk.client.ComponentClient
import akka.javasdk.http.HttpResponses
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.gwgs.akkaagentic.docs.application.{DocsAgent, KnowledgeStore}
import com.gwgs.akkaagentic.docs.domain.{AskQuestion, GuardrailAudit}

/** Synchronous HTTP surface for capability 8 (RAG-grounded Q&A). `POST /ask` retrieves the most
  * semantically relevant passages from the local corpus, has [[DocsAgent]] answer grounded strictly in
  * them, and returns the answer plus the cited source labels — directly, no start-then-poll (like
  * cap-4).
  *
  * Two design points (research R3/R4): retrieval happens **here**, once, so citations are ground truth
  * from what was actually retrieved (not model self-report — dodging cap-7's D6). The retrieved
  * passages are handed to the agent as a Java-shaped `Request`; the agent replies with a bare `String`.
  *
  * Capability 12 adds a third outcome — a guardrail refusal — without disturbing the other two.
  */
object DocsEndpoint:

  /** How many passages to retrieve and offer as grounding context. */
  private val TopK = 3

  /** Inbound body — idiomatic Scala (feature 003): annotation-free, `Option` field. Absent/null
    * `question` deserializes to `None` (rejected by validation, not a 500). Unknown props tolerated. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case class AskRequest(question: Option[String])

  /** Outbound reply — API-owned. `citedSources` are the source labels of the passages that grounded
    * the answer, and is **empty** when the assistant declined (FR-005). */
  final case class AskReply(answer: String, citedSources: List[String])

  /** Outbound reply for an interaction a guardrail refused (capability 12, FR-003/FR-005).
    *
    * A separate shape from [[AskReply]] on purpose: a block is not an answer, and a caller must be
    * able to tell a governance refusal from *"the corpus doesn't cover this"* without reading prose.
    * `blocked` is always `true`, so the discrimination survives a client that ignores status codes.
    */
  final case class BlockedReply(blocked: Boolean, rule: String, category: String, explanation: String)

  /** Read a blocked reply off the agent's [[DocsAgent.BlockedPrefix]]-tagged answer.
    *
    * `rule` and `category` are populated only when the rule tagged itself ([[GuardrailAudit]]). The
    * SDK's own `SimilarityGuard` cannot, so a jailbreak block reports them as `unknown` while still
    * carrying the SDK's explanation — enforcement never depends on the parse succeeding.
    */
  private def blockedReply(reply: String): BlockedReply =
    val (rule, category, explanation) =
      GuardrailAudit.parse(reply.stripPrefix(DocsAgent.BlockedPrefix).trim)
    BlockedReply(blocked = true, rule = rule, category = category, explanation = explanation)

  /** True when the agent's reply is the decline sentinel (normalized: trimmed, case-insensitive). A
    * decline must cite nothing. */
  private def isDecline(reply: String): Boolean =
    reply.trim.toLowerCase.startsWith(DocsAgent.DontKnow.toLowerCase)

@HttpEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class DocsEndpoint(componentClient: ComponentClient, knowledgeStore: KnowledgeStore):
  import DocsEndpoint.*

  /** Answer a question grounded in retrieved corpus passages. Validates first — a blank/absent question
    * returns `400` with no retrieval and no model call. Otherwise retrieves top-k passages, has the
    * agent answer from them, and cites the retrieved sources (or nothing, on a decline).
    *
    * Capability 12 adds one outcome: if a guardrail refuses the interaction — on the way in, before any
    * model call, or on the way out, before delivery — the block surfaces as a `422` instead of an
    * answer. The `200` answer, `200` decline and `400` validation paths are untouched (SC-002).
    */
  @Post("/ask")
  def ask(request: AskRequest): HttpResponse =
    AskQuestion.validate(request.question) match
      case Left(message) =>
        HttpResponses.badRequest(message)
      case Right(valid) =>
        val retrieved = knowledgeStore.retrieve(valid.question, TopK)
        val agentRequest = DocsAgent.Request(
          valid.question,
          retrieved.map(r => DocsAgent.Passage(r.source, r.text)).asJava
        )
        val answer = componentClient
          .forAgent()
          .inSession(UUID.randomUUID().toString) // each question is independent (FR-008); fresh session
          .dynamicCall[DocsAgent.Request, String]("docs-agent") // Scala can't use Java method refs (cap-1)
          .invoke(agentRequest)

        if answer.startsWith(DocsAgent.BlockedPrefix) then blocked(blockedReply(answer))
        else
          val citedSources =
            if isDecline(answer) then List.empty
            else retrieved.map(_.source).distinct
          HttpResponses.ok(AskReply(answer, citedSources))

  /** `422 Unprocessable Content` — a governance refusal, distinct from `200` (answer or decline) and
    * `400` (invalid input). JSON-encoded through the SDK's endpoint mapper, which carries the Scala
    * module registered by `Bootstrap` (README §3). */
  private def blocked(reply: BlockedReply): HttpResponse =
    HttpResponses.of(
      StatusCodes.UNPROCESSABLE_ENTITY,
      ContentTypes.APPLICATION_JSON,
      JsonSupport.encodeToString(reply).getBytes(StandardCharsets.UTF_8)
    )
