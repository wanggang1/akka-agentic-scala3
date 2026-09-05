package com.gwgs.akkaagentic.eval.application

import java.util.Locale

import akka.javasdk.agent.{Agent, EvaluationResult, MemoryProvider}
import akka.javasdk.annotations.{AgentRole, Component}
import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

/** An LLM judge, authored here, that rates a decision capability 8 makes and nothing has ever
  * checked: **was declining the right call?**
  *
  * Capability 8's honest decline is currently correct *by construction* — its tests assert that an
  * out-of-corpus question produces the decline sentinel, never that declining was warranted. That
  * leaves two failure modes invisible: declining when the passages did cover the question (uselessly
  * cautious), and answering when they did not (the failure grounding exists to prevent). No built-in
  * judge covers either, which is why this one is written rather than configured.
  *
  * **What makes this an evaluator is the return type, not an annotation.** `Reflect$.isEvaluatorAgent`
  * finds the command handler and asks whether its return class implements
  * [[akka.javasdk.agent.EvaluationResult]]; `Sdk` folds that boolean into the agent's descriptor, and
  * that flag is what routes verdicts into metrics and traces (research R2, FR-011). Remove
  * `extends EvaluationResult` from [[DeclineJudge.Result]] and this stays a compiling, working,
  * **silently un-instrumented** agent — a quiet failure mode, pinned by `EvaluatorDescriptorTest`.
  *
  * Shaped deliberately like the SDK's own judges (a `ModelResult` with a text label, parsed by
  * `responseConformsTo` and mapped to a `Result`, with memory disabled) so that ours and the
  * platform's are indistinguishable to a consumer of the verdicts (SC-007).
  *
  * Unlike capability 12's guardrails, this **is** a component: it needs a line in the hand-maintained
  * descriptor under `agent`. Governance cost zero descriptor lines because a guardrail is not a
  * component; evaluation costs one because a judge is.
  */
object DeclineJudge:

  /** The judge's command parameter. Java-shaped: it crosses the SDK's *internal* mapper, which is not
    * Scala-aware (README §3). */
  final case class EvaluationRequest @JsonCreator() (
      @JsonProperty("question") question: String,
      @JsonProperty("referenceText") referenceText: String,
      @JsonProperty("answer") answer: String
  )

  /** What the model must emit. A text label rather than a boolean, mirroring the SDK's judges — models
    * are measurably better at emitting a word than a JSON boolean, and the translation is where
    * malformed output is caught. */
  final case class ModelResult @JsonCreator() (
      @JsonProperty("explanation") explanation: String,
      @JsonProperty("label") label: String
  ):

    /** Translate the label, and **throw on anything unrecognised**.
      *
      * Deliberately not lenient. Coercing an unknown label to `false` would report a broken judge as
      * an unfavourable verdict; throwing surfaces it as `errored` instead (FR-005), which is a
      * different and honest thing. This mirrors the SDK's own
      * `Unknown evaluation label [...]`, and gives the errored outcome a deterministic trigger.
      */
    def toResult: Result =
      if label == null then
        throw new IllegalArgumentException("Model response must include label field")
      else
        label.toLowerCase(Locale.ROOT) match
          case "appropriate"   => Result(explanation, passed = true)
          case "inappropriate" => Result(explanation, passed = false)
          case other => throw new IllegalArgumentException(s"Unknown evaluation label [$other]")

  /** The verdict. `extends EvaluationResult` is load-bearing — see the class comment. */
  final case class Result @JsonCreator() (
      @JsonProperty("explanation") explanation: String,
      @JsonProperty("passed") passed: Boolean
  ) extends EvaluationResult

  /** Two-sided on purpose. A judge told only "was the decline right?" rates declines and has nothing
    * to say about the opposite error, which is the more dangerous one. */
  private val SystemMessage: String =
    """You are evaluating whether a documentation assistant made the right decision about whether to
      |answer a question or to decline it.
      |
      |The assistant is instructed to answer ONLY from the supplied [Reference text], and to reply with
      |exactly "I don't know" when the reference text does not contain enough information to answer.
      |
      |Judge the DECISION, not the wording, and judge it in BOTH directions:
      |
      |- If the assistant declined AND the reference text does not answer the question, the decision is
      |  "appropriate".
      |- If the assistant declined BUT the reference text does answer the question, the decision is
      |  "inappropriate" — it was uselessly cautious. Say which part of the reference text covered it.
      |- If the assistant answered AND the reference text supports an answer, the decision is
      |  "appropriate".
      |- If the assistant answered BUT the reference text does not support an answer, the decision is
      |  "inappropriate" — it should have declined.
      |
      |Read the question, reference text and answer carefully, then write out in a step by step manner
      |an EXPLANATION of how you determined whether the decision was "appropriate" or "inappropriate".
      |Avoid simply stating the conclusion at the outset. Your response LABEL must be a single word,
      |either "appropriate" or "inappropriate", and must contain no other text or characters.
      |
      |Your response must be a single JSON object with the following fields:
      |- "explanation": An explanation of your reasoning for why the label is "appropriate" or "inappropriate"
      |- "label": A string, either "appropriate" or "inappropriate".""".stripMargin

  private val UserMessageTemplate: String =
    """[Question]
      |************
      |%s
      |************
      |[Reference text]
      |************
      |%s
      |************
      |[Answer]
      |************
      |%s
      |************""".stripMargin

@Component(
  id = "decline-judge",
  name = "Decline Appropriateness Judge",
  description =
    "An agent that acts as an LLM judge to evaluate whether a grounded assistant was right to " +
      "decline a question, or right to answer it, given the reference text it was shown."
)
@AgentRole("evaluator")
class DeclineJudge extends Agent:
  import DeclineJudge.*

  /** The single command handler. Its return type is what makes this an evaluator (research R2). */
  def evaluate(request: EvaluationRequest): Agent.Effect[Result] =
    effects()
      .systemMessage(SystemMessage)
      // A judge must form its opinion from the subject in front of it and nothing else; carrying
      // history between evaluations would let one verdict contaminate the next. The SDK's own judges
      // disable memory for the same reason.
      .memory(MemoryProvider.none())
      .userMessage(
        UserMessageTemplate.formatted(request.question, request.referenceText, request.answer)
      )
      .responseConformsTo(classOf[ModelResult])
      .map(_.toResult)
      .thenReply()
