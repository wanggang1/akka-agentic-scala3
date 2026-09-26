package com.gwgs.akkaagentic.reminders.api

import java.util.concurrent.TimeUnit

import akka.http.javadsl.model.{ContentTypes, StatusCodes}
import akka.javasdk.JsonSupport
import akka.javasdk.testkit.{TestKit, TestKitSupport}
import com.gwgs.akkaagentic.reminders.api.ReminderEndpoint.{CancelReply, ReminderView}
import com.gwgs.akkaagentic.reminders.api.ReminderSchedulingEndpoint.ScheduledReminder
import com.gwgs.akkaagentic.reminders.application.ReminderStore
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{BeforeEach, Tag, Test}

/** T016 / T017 — User Story 2 over real HTTP: a cancelled reminder never fires (SC-003), and the two
  * non-cancellations are reported as what they are (FR-006).
  *
  * **This is the interop finding exercised end to end.** Every reminder here is scheduled by the Java
  * endpoint and cancelled by the Scala one, with nothing but the reminder id crossing between them.
  *
  * **What "never fires" is proven against.** The store is the arbiter: a cancel commits there first,
  * and a firing that runs anyway cannot overwrite it. So the assertion below — still `cancelled`, no
  * `firedAt`, after waiting past the delay — is the caller-observable guarantee FR-005 asks for. That
  * the *timer itself* is removed (so the runtime is spared the no-op firing) is the separate claim
  * research Q-B measured, and the probe test keeps it.
  *
  * **Real-time cost.** Proving a negative costs one wait past the delay: ~2 s here, the one unavoidable
  * wait in this class (FR-011).
  */
@Tag("slow")
class ReminderCancellationIntegrationTest extends TestKitSupport:

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT.withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")

  @BeforeEach
  def reset(): Unit = ReminderStore.clear()

  private def schedule(note: String, delaySeconds: Int): String =
    httpClient
      .POST("/reminders")
      .withRequestBody(ContentTypes.APPLICATION_JSON, s"""{"note":"$note","delaySeconds":$delaySeconds}""".getBytes("UTF-8"))
      .responseBodyAs(classOf[ScheduledReminder])
      .invoke()
      .body()
      .reminderId

  private def read(id: String): ReminderView =
    httpClient.GET(s"/reminders/$id").responseBodyAs(classOf[ReminderView]).invoke().body()

  /** Cancel, returning the status and — for 200/409 — the decoded reply. Decoded by hand because
    * `responseBodyAs` throws on a non-2xx status, and the 409's body is the point of the test. */
  private def cancel(id: String): (Int, Option[CancelReply]) =
    val response = httpClient.DELETE(s"/reminders/$id").invoke()
    val status = response.status().intValue()
    val reply =
      if status == 200 || status == 409 then
        Some(JsonSupport.getObjectMapper.readValue(response.body().toArray, classOf[CancelReply]))
      else None
    (status, reply)

  // --- T016: SC-003 — cancelled means it never fires ----------------------------------------------

  @Test
  def aCancelledReminderNeverFires(): Unit =
    val id = schedule("cancel me", delaySeconds = 1)

    val (status, reply) = cancel(id)
    assertThat(status).isEqualTo(200)
    assertThat(reply.map(_.state).orNull).isEqualTo("cancelled")

    // Wait PAST the 1 s delay: proving a negative needs the moment it would have happened to pass.
    Thread.sleep(2000)
    val after = read(id)
    assertThat(after.state).isEqualTo("cancelled")
    assertThat(after.firedAt.isDefined).isFalse()
    assertThat(after.cancelledAt.isDefined).isTrue()

  // --- T017: FR-006 — three outcomes, kept distinct -----------------------------------------------

  @Test
  def cancellingAFiredReminderIs409AndSaysFired(): Unit =
    val id = schedule("too late", delaySeconds = 1)
    Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() => assertThat(read(id).state).isEqualTo("fired"))

    val (status, reply) = cancel(id)
    assertThat(status).isEqualTo(409)
    // The specific failure FR-006 exists to prevent: the body must name what STOOD, not "cancelled".
    assertThat(reply.map(_.state).orNull).isEqualTo("fired")
    assertThat(read(id).state).isEqualTo("fired")

  @Test
  def cancellingTwiceIs409TheSecondTime(): Unit =
    val id = schedule("twice", delaySeconds = 60)
    assertThat(cancel(id)._1).isEqualTo(200)

    val (status, reply) = cancel(id)
    assertThat(status).isEqualTo(409)
    assertThat(reply.map(_.state).orNull).isEqualTo("cancelled")

  @Test
  def cancellingAnUnknownReminderIs404(): Unit =
    val (status, reply) = cancel("never-existed")
    assertThat(status).isEqualTo(404)
    assertThat(reply.isDefined).isFalse()
