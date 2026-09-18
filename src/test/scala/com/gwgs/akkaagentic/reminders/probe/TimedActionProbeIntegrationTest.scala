package com.gwgs.akkaagentic.reminders.probe

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.http.javadsl.model.ContentTypes
import akka.javasdk.testkit.{TestKit, TestKitSupport}
import com.gwgs.akkaagentic.reminders.api.ReminderSchedulingEndpoint.ScheduledReminder
import com.gwgs.akkaagentic.reminders.application.ReminderStore
import com.gwgs.akkaagentic.reminders.domain.ReminderState
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{BeforeEach, Test}
import org.slf4j.LoggerFactory

/** Phase 0's discovery probe, kept as evidence after the capability replaced its scaffolding (FR-013).
  *
  * It now answers the two interop questions **against the real surface**, so the evidence and the
  * shipped code cannot drift apart:
  *   - **Q-A** — a Scala caller cannot schedule. The Scala attempt still runs through
  *     `/probe/scala-schedule` and its run-time failure is logged verbatim; the Java control is now the
  *     production `POST /reminders` itself.
  *   - **Q-B** — a Scala caller can cancel what Java scheduled: `POST /reminders` (Java) then
  *     `DELETE /reminders/{id}` (Scala).
  *
  * Q-D (the retry contract) moved to `BoundedRetryIntegrationTest`; Q-E (the smallest reliable delay)
  * was a measurement log rather than a regression check, and lives in research.md.
  */
class TimedActionProbeIntegrationTest extends TestKitSupport:

  private val logger = LoggerFactory.getLogger(classOf[TimedActionProbeIntegrationTest])

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT.withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")

  @BeforeEach
  def reset(): Unit = ReminderStore.clear()

  private def hasFired(id: String): Boolean = ReminderStore.get(id).exists(_.state == ReminderState.Fired)

  private def scheduleFromJava(note: String): String =
    httpClient
      .POST("/reminders")
      .withRequestBody(ContentTypes.APPLICATION_JSON, s"""{"note":"$note","delaySeconds":1}""".getBytes("UTF-8"))
      .responseBodyAs(classOf[ScheduledReminder])
      .invoke()
      .body()
      .reminderId

  /** Q-A — can a Scala caller schedule at all? It compiles; this records what happens at run time. */
  @Test
  def recordWhetherScalaCanSchedule(): Unit =
    val name = s"scala-${UUID.randomUUID().toString.take(8)}"
    val verdict = httpClient.POST(s"/probe/scala-schedule/$name/300").invoke().body().utf8String
    logger.info("Q-A probe >>> Scala schedule verdict: {}", verdict)

    // The verdict IS the evidence, so it is asserted rather than only logged: the failure happens in
    // `deferred()`, and it names the synthetic lambda (research Q-A).
    assertThat(verdict).startsWith("FAILED: java.lang.IllegalArgumentException")
    assertThat(verdict).contains("$anonfun$")

    Thread.sleep(1000)
    assertThat(hasFired(name)).isFalse()

  /** Q-A control — the same action, scheduled from Java, fires. That is what makes Q-A a statement
    * about the Scala caller rather than about our usage. */
  @Test
  def theJavaPathIsTheControl(): Unit =
    val id = scheduleFromJava("java-control")
    Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() => hasFired(id))
    logger.info("Q-A control >>> Java-scheduled timer fired; note carried = {}", ReminderStore.get(id).map(_.note))

  /** Q-B — ONE FAMILY, BOTH SIDES OF THE WALL: Scala cancels a timer Java scheduled. */
  @Test
  def scalaCancelsATimerJavaScheduled(): Unit =
    val id = scheduleFromJava("cancel-me")
    val status = httpClient.DELETE(s"/reminders/$id").invoke().status().intValue()
    assertThat(status).isEqualTo(200)

    Thread.sleep(2000)
    assertThat(hasFired(id)).isFalse()
    logger.info("Q-B probe >>> Java-scheduled, Scala-cancelled: fired after the delay? {}", hasFired(id))
