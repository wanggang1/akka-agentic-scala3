package com.gwgs.akkaagentic.eval.application

import java.util.UUID

import scala.util.{Failure, Success, Try}

import akka.javasdk.agent.evaluator.HallucinationEvaluator
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** T003/T004 — the discovery test that gates every production change in capability 13.
  *
  * Research R1 and R3 were both settled by reading the shipped bytecode, and both are load-bearing:
  * if either is wrong, the design changes rather than the code. Capability 12's equivalent probe
  * disproved two design assumptions before any production line was written, so cap-13 pays the same
  * one-test premium before committing to a shape.
  *
  * Four facts, none of which has actually been executed before this test:
  *
  *   a. **R1** — does `dynamicCall("hallucination-evaluator")` reach an agent the **SDK owns**?
  *      `ComponentLocator$` lists the three evaluators in `agentProvidedComponents`, so they should be
  *      in `agentClassById` alongside ours. Every previous `dynamicCall` in this project targeted an
  *      agent *we* declared; this is the first time the escape hatch is pointed at a runtime-owned
  *      component. Capabilities 4, 6 and 11 each had to quarantine Java when they needed one.
  *   b. **R3** — does `withModelProvider(classOf[HallucinationEvaluator], …)` actually override the
  *      model? `LlmAsJudge` sets one *explicitly* from
  *      `akka.javasdk.agent.evaluators.hallucination-evaluator.model-provider`, so a naive reading
  *      says the test provider is ignored and every test would call a real model. `AgentImpl` reads
  *      `overrideModelProvider(id).getOrElse(requestModel.modelProvider)`, which says otherwise. If
  *      the naive reading wins, the built-in judge is live-only and the whole testing design changes.
  *   c. what the SDK's own parser requires of a scripted reply, and how `label` maps to `passed`.
  *   d. what an **unrecognised** label does — expected to throw from the SDK's own
  *      `toEvaluationResult`. This is not curiosity: it is the deterministic trigger the `errored`
  *      outcome (FR-005, SC-004) is designed around, and it must come from the SDK rather than from
  *      us breaking something.
  *
  * **T004, the negative control (FR-013).** The documentation calls a built-in judge with a Java
  * method reference. This test also *attempts* the Scala equivalent and records exactly what a
  * developer following the docs would see — "it does not work" is not a finding; the message is.
  *
  * The test does not assume its answers: it classifies what it observes and logs it, then asserts
  * only the properties the capability actually depends on.
  */
class BuiltInJudgeProbeIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(getClass)
  private val judgeModel = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("""akka.javasdk.agent.googleai-gemini.api-key = "n/a"""")
      // R3 under test: the built-in evaluator is an SDK-owned class, and it sets its own model
      // explicitly. Registering a provider for it is only meaningful if the override wins.
      .withModelProvider(classOf[HallucinationEvaluator], judgeModel)

  private def judge(
      query: String,
      referenceText: String,
      answer: String
  ): Try[HallucinationEvaluator.Result] =
    Try(
      componentClient
        .forAgent()
        .inSession(UUID.randomUUID().toString)
        .dynamicCall[HallucinationEvaluator.EvaluationRequest, HallucinationEvaluator.Result](
          "hallucination-evaluator"
        )
        .invoke(new HallucinationEvaluator.EvaluationRequest(query, referenceText, answer))
    )

  /** (a) + (b) + (c): the escape hatch reaches an SDK-owned agent, the override beats the
    * evaluator's explicit model, and a `factual` label means `passed`.
    */
  @Test
  def dynamicCallReachesTheSdkOwnedJudgeAndTheModelOverrideWins(): Unit =
    judgeModel.fixedResponse(
      """{"explanation":"The answer restates the reference text.","label":"factual"}"""
    )

    val outcome = judge(
      "what makes agent work survive a restart?",
      "[1] (durability-tasks) The runtime persists the task and the agent's process state.",
      "The runtime persists the task, so work survives a restart."
    )

    outcome match
      case Success(result) =>
        logger.info(
          "R1/R3 MEASURED: dynamicCall reached the SDK-owned judge AND the test model override won. " +
            "passed={} explanation={}",
          result.passed,
          result.explanation
        )
        assertThat(result.passed).isTrue()
        assertThat(result.explanation).contains("restates the reference text")
      case Failure(e) =>
        // Two distinguishable failure worlds, and the message tells them apart: an unknown component
        // id means R1 is wrong (the evaluators are not in agentClassById); anything about a model
        // connection means R3 is wrong (the explicit .model() won and a real model was called).
        logger.error("R1/R3 MEASURED NEGATIVE: {}: {}", e.getClass.getName, e.getMessage)
        throw new AssertionError(
          s"probe failed — the design depends on this working: ${e.getClass.getName}: ${e.getMessage}",
          e
        )

  /** (c), the other direction: `hallucinated` means `passed = false`. A judge that always passes
    * would make every later test vacuous.
    */
  @Test
  def hallucinatedLabelMapsToNotPassed(): Unit =
    judgeModel.fixedResponse(
      """{"explanation":"The answer asserts a fact absent from the reference text.","label":"hallucinated"}"""
    )

    val result = judge(
      "what makes agent work survive a restart?",
      "[1] (durability-tasks) The runtime persists the task and the agent's process state.",
      "Work survives because every agent writes a checkpoint file to disk every 30 seconds."
    ).get

    assertThat(result.passed).isFalse()
    logger.info("R3 MEASURED: hallucinated -> passed=false, explanation={}", result.explanation)

  /** (d): an unrecognised label must fail, and the failure must come from the SDK's own
    * `toEvaluationResult`. This is the deterministic trigger for the `errored` verdict.
    */
  @Test
  def anUnrecognisedLabelFailsRatherThanSilentlyPassing(): Unit =
    judgeModel.fixedResponse("""{"explanation":"I am not sure.","label":"maybe"}""")

    judge("q", "[1] (s) reference", "answer") match
      case Success(result) =>
        throw new AssertionError(
          s"expected an unrecognised label to fail, but got passed=${result.passed} — the errored " +
            s"outcome would then have no deterministic trigger"
        )
      case Failure(e) =>
        logger.info("R3(d) MEASURED: unknown label -> {}: {}", e.getClass.getName, e.getMessage)
        // Record the shape; the message text is the finding, so it is asserted loosely on purpose.
        assertThat((e.getMessage: Object)).isNotNull()

  /** T004 — the negative control for FR-013.
    *
    * The documented form is `.method(HallucinationEvaluator::evaluate)`. Scala has no `::` method
    * reference; the nearest equivalent is a lambda adapted to `akka.japi.function.Function2`, which
    * **compiles** and then fails at resolution because `MethodRefResolver` reads the
    * `SerializedLambda`'s `implMethodName` and a Scala lambda compiles to a synthetic `$anonfun`.
    *
    * The point of this test is not that it fails — it is to capture *what a Scala developer actually
    * sees* when they follow the documentation, so the finding can quote it.
    */
  @Test
  def theDocumentedMethodReferenceFormFailsFromScala(): Unit =
    judgeModel.fixedResponse("""{"explanation":"unused","label":"factual"}""")

    val attempt = Try(
      componentClient
        .forAgent()
        .inSession(UUID.randomUUID().toString)
        .method[HallucinationEvaluator, HallucinationEvaluator.EvaluationRequest, HallucinationEvaluator.Result](
          (agent, request) => agent.evaluate(request)
        )
        .invoke(new HallucinationEvaluator.EvaluationRequest("q", "[1] (s) reference", "answer"))
    )

    attempt match
      case Failure(e) =>
        logger.info(
          "T004 MEASURED — the documented method-reference form from Scala fails with {}: {}",
          e.getClass.getName,
          e.getMessage
        )
        assertThat((e.getMessage: Object)).isNotNull()
      case Success(result) =>
        // Not a failure of the capability — a genuinely surprising positive that would rewrite the
        // project's oldest finding. Recorded loudly rather than quietly asserted away.
        logger.warn(
          "T004 MEASURED — SURPRISE: the Scala lambda RESOLVED. passed={}. The method-reference wall " +
            "does not apply to the agent client's method(...) overloads; revisit README §2.",
          result.passed
        )
