package com.gwgs.akkaagentic.eval.application

import java.util.UUID

import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

import akka.javasdk.agent.EvaluationResult
import akka.javasdk.agent.evaluator.HallucinationEvaluator
import akka.javasdk.client.ComponentClient
import com.gwgs.akkaagentic.docs.application.{DocsAgent, KnowledgeStore}
import com.gwgs.akkaagentic.eval.domain.EvaluationApplicability
import com.gwgs.akkaagentic.eval.domain.EvaluationApplicability.Applicability
import com.gwgs.akkaagentic.eval.domain.ReferenceText

/** Orchestration for capability 13: answer a question exactly as capability 8 does, then have LLM
  * judges rate the result.
  *
  * A **plain class, not a component** — so it takes no descriptor entry and gets no injection of its
  * own; [[com.gwgs.akkaagentic.eval.api.EvaluationEndpoint]] constructs it with the dependencies the
  * SDK injected into *it*.
  */
object AnswerEvaluator:

  /** Capability 8's retrieval width, restated rather than shared.
    *
    * This is the one deliberate duplication in the capability (research D9). Extracting a shared
    * pipeline would mean editing `DocsEndpoint`, which SC-003 forbids — capability 8's sources must
    * end byte-identical. The duplication is *also the thing under test*: this sequence must reproduce
    * capability 8's exactly, and a parity test proves it does, which is a stronger guarantee than
    * shared code would have been an excuse for not checking.
    */
  private val TopK = 3

  /** The SDK's built-in grounding judge. Its `COMPONENT_ID` constant is package-private, so the id is
    * restated here; a probe test pins that it still resolves (research R1). */
  val HallucinationJudgeId: String = "hallucination-evaluator"

  /** The judge authored in this capability — an ordinary component of ours, listed in the descriptor
    * under `agent`. Reached the same way as the SDK's, which is the point: to a consumer of verdicts
    * the two are indistinguishable (SC-007). */
  val DeclineJudgeId: String = "decline-judge"

  /** Four outcomes, and the last two are **not** `Failed`.
    *
    * A judge that could not form an opinion (`Errored`) and a subject that cannot be judged
    * (`NotApplicable`) are different from a verdict of "no". Collapsing either into `Failed` would
    * report a working system as a broken one (FR-005, FR-012).
    */
  enum Outcome:
    case Passed, Failed, Errored, NotApplicable

  final case class Verdict(judge: String, outcome: Outcome, explanation: String)

  final case class Evaluation(
      evaluationId: String,
      question: String,
      answer: String,
      citedSources: List[String],
      referenceText: String,
      verdicts: List[Verdict]
  )

final class AnswerEvaluator(componentClient: ComponentClient, knowledgeStore: KnowledgeStore):
  import AnswerEvaluator.*

  /** Retrieve, answer, then judge. The answer is produced by the **same** call `DocsEndpoint` makes,
    * so capability 12's guardrails apply here too — which is what makes the refused path reachable
    * end to end without this capability configuring any rule.
    */
  def evaluate(question: String, judgesEnabled: Boolean = true): Evaluation =
    // ONE session id for the whole evaluation: the assistant turn and both judges. The judges keep
    // memory disabled, so nothing leaks between them — what sharing the session buys is that the
    // answer and the verdicts about it land in a single trace, which is how a developer correlates a
    // verdict with the interaction it judged (FR-011). The SDK's own documented EvaluationConsumer
    // does the same, keying every evaluator call on the task id.
    val evaluationId = UUID.randomUUID().toString
    val retrieved = knowledgeStore.retrieve(question, TopK)
    val answer = askDocsAgent(evaluationId, question, retrieved)
    val referenceText = ReferenceText.render(retrieved.map(r => r.source -> r.text))
    val refused = answer.startsWith(DocsAgent.BlockedPrefix)

    val verdicts =
      if !judgesEnabled then List.empty // FR-009: switched off, the answer still stands
      else
        EvaluationApplicability.of(answer, referenceText, DocsAgent.BlockedPrefix) match
          case Applicability.NotApplicable(reason) =>
            // No judge model is called: judging a refusal, or judging against no reference material,
            // yields a confident and meaningless verdict (research R6).
            judgeIds.map(Verdict(_, Outcome.NotApplicable, reason))
          case Applicability.Applicable =>
            judge(evaluationId, question, referenceText, answer)

    Evaluation(
      evaluationId = evaluationId,
      question = question,
      // A refusal is not an answer, and the sentinel is an internal marker — the caller learns *that*
      // it was refused from the verdicts, per contracts/evaluate-endpoint.md.
      answer = if refused then "" else answer,
      citedSources =
        if refused || isDecline(answer) then List.empty
        else retrieved.map(_.source).distinct,
      referenceText = referenceText,
      verdicts = verdicts
    )

  /** Which judges are consulted, and in what order — one the platform ships, one we wrote. */
  private def judgeIds: List[String] = List(HallucinationJudgeId, DeclineJudgeId)

  /** Both judges run **independently**: each is wrapped separately, so one erroring never suppresses
    * the other's verdict (FR-007, SC-004). They are also called identically — by component id through
    * `dynamicCall` — even though one belongs to the SDK and one to us. That symmetry is the finding,
    * not a convenience: the agent client's string-keyed escape hatch reaches components we do not own
    * (research R1), where the documented `.method(Evaluator::evaluate)` form cannot be written in
    * Scala at all (T004).
    */
  private def judge(
      sessionId: String,
      question: String,
      referenceText: String,
      answer: String
  ): List[Verdict] =
    List(
      verdictOf(HallucinationJudgeId) {
        componentClient
          .forAgent()
          .inSession(sessionId)
          .dynamicCall[HallucinationEvaluator.EvaluationRequest, HallucinationEvaluator.Result](
            HallucinationJudgeId
          )
          .invoke(new HallucinationEvaluator.EvaluationRequest(question, referenceText, answer))
      },
      verdictOf(DeclineJudgeId) {
        componentClient
          .forAgent()
          .inSession(sessionId)
          .dynamicCall[DeclineJudge.EvaluationRequest, DeclineJudge.Result](DeclineJudgeId)
          .invoke(DeclineJudge.EvaluationRequest(question, referenceText, answer))
      }
    )

  /** Run one judge and turn whatever happens into a verdict.
    *
    * Catching broadly is deliberate and measured, not lazy. The SDK's own `toEvaluationResult` throws
    * `IllegalArgumentException` on a label outside its vocabulary, but that type is **erased at the
    * component-client boundary** into `kalix.runtime.CorrelatedRuntimeException` — capability 12
    * measured the same erasure for guardrail exceptions, and the T003 probe confirmed it here. So the
    * type cannot be matched on; the *message* survives, which is what the explanation needs.
    *
    * A judge failure must never become a failed request (FR-007): the answer is already computed and
    * is returned regardless.
    */
  private def verdictOf(judgeId: String)(call: => EvaluationResult): Verdict =
    Try(call) match
      case Success(result) =>
        Verdict(judgeId, if result.passed then Outcome.Passed else Outcome.Failed, result.explanation)
      case Failure(e) =>
        Verdict(
          judgeId,
          Outcome.Errored,
          s"the judge returned an unusable response: ${Option(e.getMessage).getOrElse(e.getClass.getName)}"
        )

  private def askDocsAgent(
      sessionId: String,
      question: String,
      retrieved: List[KnowledgeStore.Retrieved]
  ): String =
    componentClient
      .forAgent()
      .inSession(sessionId)
      .dynamicCall[DocsAgent.Request, String]("docs-agent")
      .invoke(
        DocsAgent.Request(question, retrieved.map(r => DocsAgent.Passage(r.source, r.text)).asJava)
      )

  /** Capability 8's decline rule, restated for the same reason as `TopK` and pinned by the parity
    * test: a decline cites nothing. */
  private def isDecline(answer: String): Boolean =
    answer.trim.toLowerCase.startsWith(DocsAgent.DontKnow.toLowerCase)
