package com.gwgs.akkaagentic.docs.api

import java.util.UUID

import scala.jdk.CollectionConverters.*

import akka.http.javadsl.model.HttpResponse
import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.http.{HttpEndpoint, Post}
import akka.javasdk.client.ComponentClient
import akka.javasdk.http.HttpResponses
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.gwgs.akkaagentic.docs.application.{DocsAgent, KnowledgeStore}
import com.gwgs.akkaagentic.docs.domain.AskQuestion

/** Synchronous HTTP surface for capability 8 (RAG-grounded Q&A). `POST /ask` retrieves the most
  * semantically relevant passages from the local corpus, has [[DocsAgent]] answer grounded strictly in
  * them, and returns the answer plus the cited source labels — directly, no start-then-poll (like
  * cap-4).
  *
  * Two design points (research R3/R4): retrieval happens **here**, once, so citations are ground truth
  * from what was actually retrieved (not model self-report — dodging cap-7's D6). The retrieved
  * passages are handed to the agent as a Java-shaped `Request`; the agent replies with a bare `String`.
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
        val citedSources =
          if isDecline(answer) then List.empty
          else retrieved.map(_.source).distinct
        HttpResponses.ok(AskReply(answer, citedSources))
