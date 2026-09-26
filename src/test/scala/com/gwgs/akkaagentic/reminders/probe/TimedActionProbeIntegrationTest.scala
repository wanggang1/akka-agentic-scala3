package com.gwgs.akkaagentic.reminders.probe

import java.util.UUID

import akka.javasdk.testkit.{TestKit, TestKitSupport}
import com.gwgs.akkaagentic.reminders.application.ReminderStore
import com.gwgs.akkaagentic.reminders.domain.ReminderState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Tag, Test}
import org.slf4j.LoggerFactory

/** Phase 0's discovery probe, reduced to the one piece of evidence nothing else carries (FR-013): **a
  * Scala caller cannot schedule a timed action.**
  *
  * The rest of what this class used to measure now lives in the tests that ship the capability, so the
  * suite proves each thing once:
  *   - Q-A's **Java control** — the same action, scheduled from Java, fires — is
  *     `ReminderSchedulingIntegrationTest`: every reminder there is scheduled by the Java endpoint.
  *   - **Q-B** — Scala cancels a timer Java scheduled — is `ReminderCancellationIntegrationTest`.
  *   - **Q-D** — the retry bound — is `BoundedRetryIntegrationTest`.
  *   - **Q-C** (durability) and **Q-E** (timing) were measurements, not regression checks; they are
  *     recorded in specs/017 research.md.
  */
@Tag("slow")
class TimedActionProbeIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(classOf[TimedActionProbeIntegrationTest])

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT.withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")

  @BeforeEach
  def reset(): Unit = ReminderStore.clear()

  /** Q-A — the documented scheduling form, written as a Scala lambda, compiles and then fails at run
    * time. Asserted, not only logged: if a future SDK let Scala schedule, this is the test that says so. */
  @Test
  def aScalaCallerCannotScheduleATimedAction(): Unit =
    val name = s"scala-${UUID.randomUUID().toString.take(8)}"
    val verdict = httpClient.POST(s"/probe/scala-schedule/$name/300").invoke().body().utf8String
    logger.info("Q-A probe >>> Scala schedule verdict: {}", verdict)

    // The failure happens in `deferred()` and names the synthetic lambda (research Q-A).
    assertThat(verdict).startsWith("FAILED: java.lang.IllegalArgumentException")
    assertThat(verdict).contains("ReminderProbeEndpoint::$anonfun$")

    // And nothing was scheduled: no timer fires for it.
    Thread.sleep(1000)
    assertThat(ReminderStore.get(name).exists(_.state == ReminderState.Fired)).isFalse()
