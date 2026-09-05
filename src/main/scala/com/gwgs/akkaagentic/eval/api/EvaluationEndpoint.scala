package com.gwgs.akkaagentic.eval.api

import scala.util.Try

import akka.http.javadsl.model.HttpResponse
import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.http.{HttpEndpoint, Post}
import akka.javasdk.client.ComponentClient
import akka.javasdk.http.HttpResponses
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.typesafe.config.Config
import com.gwgs.akkaagentic.docs.application.KnowledgeStore
import com.gwgs.akkaagentic.docs.domain.AskQuestion
import com.gwgs.akkaagentic.eval.application.AnswerEvaluator

/** Synchronous HTTP surface for capability 13 (LLM-as-judge evaluation). `POST /evaluate` answers a
  * question exactly as capability 8's `POST /ask` does, then returns what the judges thought of the
  * result.
  *
  * **Nothing here gates anything.** A failed verdict is a *successful* evaluation, so every outcome
  * except an invalid request is `200` (FR-008, contracts/evaluate-endpoint.md). That includes a
  * refused interaction: `/ask` answers a block with `422` because there the block is the outcome the
  * caller asked for, while here the caller asked for an evaluation and "it was refused, so there was
  * nothing to judge" is a complete answer to that question.
  *
  * This surface exists rather than a background hook because the SDK has no interaction-completion
  * source for a request-based agent — `@Consume.From*` covers entities, workflows, topics and service
  * streams only, and the documented `EvaluationConsumer` actually consumes `TaskEntity`, which
  * capability 8 does not have (research R4). The consequence is favourable: capability 8's sources are
  * not touched at all (SC-003).
  */
object EvaluationEndpoint:

  /** Inbound body — idiomatic Scala (feature 003): annotation-free, `Option` field, unknown
    * properties tolerated. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  final case class EvaluateRequest(question: Option[String])

  /** One judge's opinion. `outcome` is a string rather than the domain enum so the wire contract does
    * not move when the enum does (Constitution II, API isolation). */
  final case class VerdictReply(judge: String, outcome: String, explanation: String)

  final case class EvaluateReply(
      evaluationId: String,
      question: String,
      answer: String,
      citedSources: List[String],
      verdicts: List[VerdictReply]
  )

  /** The config key that turns the judges off without a code change (FR-009). Defaulting to `true`
    * when absent keeps a configuration that predates this capability working. */
  private val EnabledKey = "eval.enabled"

  /** `referenceText` is deliberately **not** on the wire: it can be large, and `citedSources` already
    * identifies what grounded the answer. That the judges saw exactly those passages is proven by a
    * test that inspects the judge's actual input, not by echoing it back (SC-002).
    *
    * `evaluationId` **is** on the wire: it is the session the assistant turn and both judges ran in,
    * so it is what correlates this response with the interaction's traces. */
  private def toApi(verdict: AnswerEvaluator.Verdict): VerdictReply =
    VerdictReply(verdict.judge, outcomeName(verdict.outcome), verdict.explanation)

  private def outcomeName(outcome: AnswerEvaluator.Outcome): String = outcome match
    case AnswerEvaluator.Outcome.Passed        => "passed"
    case AnswerEvaluator.Outcome.Failed        => "failed"
    case AnswerEvaluator.Outcome.Errored       => "errored"
    case AnswerEvaluator.Outcome.NotApplicable => "not-applicable"

@HttpEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class EvaluationEndpoint(
    componentClient: ComponentClient,
    knowledgeStore: KnowledgeStore,
    config: Config
):
  import EvaluationEndpoint.*

  private val evaluator = new AnswerEvaluator(componentClient, knowledgeStore)

  /** Judges cost model calls, so they are switchable off by configuration alone (FR-009, SC-005).
    * Read per request rather than cached, so an override applies without a restart. */
  private def judgesEnabled: Boolean =
    Try(config.getBoolean(EnabledKey)).getOrElse(true)

  /** Answer, then judge. Validation runs **first** — a blank or absent question is rejected before
    * retrieval, before the assistant, and before any judge (FR-010). Capability 8's `AskQuestion` is
    * reused unchanged; no new validation type is introduced.
    */
  @Post("/evaluate")
  def evaluate(request: EvaluateRequest): HttpResponse =
    AskQuestion.validate(request.question) match
      case Left(message) =>
        HttpResponses.badRequest(message)
      case Right(valid) =>
        val evaluation = evaluator.evaluate(valid.question, judgesEnabled)
        HttpResponses.ok(
          EvaluateReply(
            evaluationId = evaluation.evaluationId,
            question = evaluation.question,
            answer = evaluation.answer,
            citedSources = evaluation.citedSources,
            verdicts = evaluation.verdicts.map(toApi)
          )
        )
