package com.gwgs.akkaagentic.docs.application

import java.util.UUID

import scala.util.{Failure, Success, Try}

import akka.javasdk.agent.{Guardrail, GuardrailContext, TextGuardrail}
import akka.javasdk.testkit.{TestKit, TestModelProvider}
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

// ---------------------------------------------------------------------------------------------
// The three Scala class forms under test. All three always pass, so the ONLY thing that can differ
// between them is whether the runtime can construct them at all — which is the whole question.
//
// They live in TEST sources on purpose: the `object` form must never be reachable from
// `src/main/resources/application.conf`.
// ---------------------------------------------------------------------------------------------

/** Form 1 — the loader's first attempt: a single, plain `(GuardrailContext)` parameter list. */
class CtxFormProbe(ctx: GuardrailContext) extends TextGuardrail:
  override def evaluate(text: String): Guardrail.Result = Guardrail.Result.OK

/** Form 2 — the loader's second attempt: no constructor parameters. Undocumented but real. */
class NoArgFormProbe extends TextGuardrail:
  override def evaluate(text: String): Guardrail.Result = Guardrail.Result.OK

/** Form 3 — the form predicted to fail, which **does not**.
  *
  * A Scala `object` compiles to a final class (`ObjectFormGuard$`) whose only constructor is
  * `private`, reachable in ordinary code solely through the static `MODULE$` field — verified with
  * `javap -p` on the compiled artifact. The prediction was that neither of the loader's two attempts
  * could reach it. That prediction was wrong, because Akka's `ReflectiveDynamicAccess` calls
  * **`setAccessible(true)`** on the constructor it finds before invoking it (verified in
  * `akka-actor` bytecode: `getDeclaredConstructor` → `setAccessible` → `newInstance`). Private is no
  * obstacle.
  *
  * The sting is elsewhere, and this counter is how the test catches it: the runtime constructs a
  * **fresh instance**, not `MODULE$`. Anything the singleton holds — a lazily built index, a cache, a
  * counter like this one — belongs to an instance the rest of your code can never see.
  */
object ObjectFormGuard extends TextGuardrail:
  @volatile var evaluations: Int = 0
  @volatile var lastEvaluatorHash: Int = 0
  override def evaluate(text: String): Guardrail.Result =
    evaluations += 1
    lastEvaluatorHash = System.identityHashCode(this)
    Guardrail.Result.OK

/** T022–T024 — the capability's headline experiment: which Scala class forms the runtime's reflective
  * guardrail loading accepts, which it rejects, and what happens when a declaration is simply wrong.
  *
  * Unlike every other test class here this one does **not** extend `TestKitSupport`, because each case
  * needs its own runtime with its own deliberately-varied configuration — including configurations
  * expected not to start at all.
  *
  * The helper does not assume *where* a bad declaration fails. A guardrail could plausibly be
  * constructed eagerly at startup or lazily on first use, and the difference matters: lazy
  * construction would mean a typo leaves an agent silently unguarded until the first request (FR-010).
  * So the outcome is classified rather than asserted up front, and the classification is the finding.
  */
class GuardrailLoadingIntegrationTest:

  private val logger = LoggerFactory.getLogger(getClass)

  private enum Outcome:
    case Loaded
    case FailedAtStartup(detail: String)
    case FailedOnUse(detail: String)

  import Outcome.*

  private def describe(t: Throwable): String =
    Iterator
      .iterate(t)(_.getCause)
      .takeWhile(_ != null)
      .map(e => s"${e.getClass.getSimpleName}: ${e.getMessage}")
      .mkString(" <- ")

  /** Start a runtime with one extra guardrail declared, exercise `docs-agent`, and report where (if
    * anywhere) the declaration failed. */
  private def outcomeOf(ruleName: String, className: String): Outcome =
    val model = new TestModelProvider()
    model.fixedResponse("A short grounded answer.")
    val settings = TestKit.Settings.DEFAULT
      .withAdditionalConfig(s"""
        |akka.javasdk.agent.googleai-gemini.api-key = "n/a"
        |akka.javasdk.agent.guardrails."$ruleName" {
        |  class       = "$className"
        |  agents      = ["docs-agent"]
        |  agent-roles = []
        |  category    = FORMAT
        |  report-only = false
        |  use-for     = ["model-request"]
        |}
        |""".stripMargin)
      .withModelProvider(classOf[DocsAgent], model)

    val testKit = new TestKit(settings)
    Try(testKit.start()) match
      case Failure(startupError) =>
        Try(testKit.stop())
        FailedAtStartup(describe(startupError))
      case Success(started) =>
        try
          Try(
            started.getComponentClient
              .forAgent()
              .inSession(UUID.randomUUID().toString)
              .dynamicCall[DocsAgent.Request, String]("docs-agent")
              .invoke(DocsAgent.Request("how does session memory work?", java.util.List.of()))
          ) match
            case Success(reply) if reply.startsWith(DocsAgent.BlockedPrefix) => FailedOnUse(reply)
            case Success(_)                                                 => Loaded
            case Failure(callError)                                         => FailedOnUse(describe(callError))
        finally Try(started.stop())

  /** R1 row 1 — the settings-taking form loads (already shown in production by `LinkedAnswerGuard`;
    * pinned here as part of the complete three-form comparison). */
  @Test
  def theContextTakingClassFormLoads(): Unit =
    val outcome = outcomeOf("probe ctx form", classOf[CtxFormProbe].getName)
    logger.info("R1 (GuardrailContext) form >>> {}", outcome)
    assertThat(outcome.toString).isEqualTo(Loaded.toString)

  /** R1 row 2 — the no-arg form loads on the loader's second attempt. Undocumented, and real. */
  @Test
  def theNoArgClassFormLoads(): Unit =
    val outcome = outcomeOf("probe no-arg form", classOf[NoArgFormProbe].getName)
    logger.info("R1 no-arg form >>> {}", outcome)
    assertThat(outcome.toString).isEqualTo(Loaded.toString)

  /** R1 row 3 — **the prediction was wrong, and the reason sharpens the whole hazard class.**
    *
    * The `object` form was predicted to fail both constructor attempts because its only constructor is
    * private. It loads. Akka's `ReflectiveDynamicAccess` calls **`setAccessible(true)`** on the
    * constructor it finds before invoking it, so `private` is no obstacle — and the SDK's own
    * `reference.conf`, which requires the class to "be public and have a public constructor", is
    * describing a rule the runtime does not actually enforce.
    *
    * The runtime does get a **different instance** from `MODULE$`, asserted below. That turns out to
    * be harmless in Scala 3 for a reason worth knowing rather than assuming: a Scala `object`'s
    * fields compile to **static** fields on the module class (`private static volatile int
    * evaluations`, initialised in `<clinit>`), and the module constructor body is empty. So both
    * instances share all state — which is why `evaluations` reads 1 here even though the evaluating
    * object is not the one this test can name.
    *
    * The practical conclusion is unchanged but now rests on the right reason: prefer a plain `class`,
    * because the object form works by a scalac implementation detail rather than by anything the SDK
    * promises.
    */
  @Test
  def theObjectFormLoadsAsAFreshInstanceNotTheSingleton(): Unit =
    ObjectFormGuard.evaluations = 0
    val outcome = outcomeOf("probe object form", ObjectFormGuard.getClass.getName)
    logger.info(
      "R1 object form >>> {} | evaluations={} | evaluator={} | MODULE$={}",
      outcome,
      ObjectFormGuard.evaluations,
      ObjectFormGuard.lastEvaluatorHash,
      System.identityHashCode(ObjectFormGuard)
    )
    // It loads — contradicting the bytecode-derived prediction in research R1.
    assertThat(outcome.toString).isEqualTo(Loaded.toString)

    // The evaluating object is NOT the singleton every other line of Scala refers to.
    assertThat(ObjectFormGuard.lastEvaluatorHash).isNotEqualTo(System.identityHashCode(ObjectFormGuard))

    // ...and yet the state is shared, because scalac made it static. Both facts together are the
    // finding: it works, but not for the reason the source code appears to say.
    assertThat(ObjectFormGuard.evaluations).isEqualTo(1)

  /** FR-010 / R4 — a misspelled class must not leave the agent silently unguarded. Whether it fails
    * at startup or on first use is itself the finding; what must not happen is that it passes. */
  @Test
  def aMisspelledClassDoesNotLeaveTheAgentSilentlyUnguarded(): Unit =
    val outcome = outcomeOf("probe missing class", "com.gwgs.akkaagentic.docs.application.NoSuchGuard")
    logger.info("FR-010 missing class >>> {}", outcome)
    assertThat(outcome.toString).isNotEqualTo(Loaded.toString)
