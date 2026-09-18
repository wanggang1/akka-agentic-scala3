package com.gwgs.akkaagentic.reminders.api

import java.util.concurrent.TimeUnit

import akka.http.javadsl.model.{ContentTypes, StatusCodes}
import akka.javasdk.testkit.{TestKit, TestKitSupport}
import com.gwgs.akkaagentic.reminders.api.ReminderEndpoint.ReminderView
import com.gwgs.akkaagentic.reminders.api.ReminderSchedulingEndpoint.ScheduledReminder
import com.gwgs.akkaagentic.reminders.application.ReminderStore
import com.gwgs.akkaagentic.reminders.domain.ReminderRequest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{BeforeEach, Test}

/** T009 / T010 — User Story 1 over real HTTP: schedule, see it pending, see it fire (SC-001, SC-002),
  * and see invalid input rejected with nothing scheduled (FR-007, SC-005).
  *
  * **A Scala test of a Java endpoint.** `httpClient` holds no method reference, so this test is not
  * claimed by the wall even though the endpoint it drives is — the capability-11 and 14 outcome.
  *
  * **Real-time cost.** Firing cannot be observed without waiting: the testkit's `TimedActionTestkit`
  * invokes an action directly, which proves what the action does but never that a timer fired (research
  * Q-E). Delays here are 1 s — the floor `ReminderRequest.MinDelay` sets at the HTTP surface — so each
  * firing assertion costs ~1.1 s of wall-clock time (measured: 1.175 s and 1.097 s — the 1 s delay
  * plus Q-E's ~100 ms overhead). Everything else in this class is milliseconds (FR-011, SC-009).
  *
  * No model anywhere: a reminder carries a note, and needs no language model to do it.
  */
class ReminderSchedulingIntegrationTest extends TestKitSupport:

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT.withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")

  @BeforeEach
  def reset(): Unit = ReminderStore.clear()

  /** Raw JSON rather than a typed body, so "absent field" means genuinely absent on the wire. */
  private def postRaw(json: String) =
    httpClient.POST("/reminders").withRequestBody(ContentTypes.APPLICATION_JSON, json.getBytes("UTF-8")).invoke()

  private def schedule(note: String, delaySeconds: Int): ScheduledReminder =
    val escaped = note.replace("\\", "\\\\").replace("\"", "\\\"")
    val response = httpClient
      .POST("/reminders")
      .withRequestBody(ContentTypes.APPLICATION_JSON, s"""{"note":"$escaped","delaySeconds":$delaySeconds}""".getBytes("UTF-8"))
      .responseBodyAs(classOf[ScheduledReminder])
      .invoke()
    assertThat(response.status()).isEqualTo(StatusCodes.CREATED)
    assertThat(response.httpResponse().getHeader("Location").map(_.value()).orElse(""))
      .isEqualTo(s"/reminders/${response.body().reminderId}")
    response.body()

  private def read(id: String): ReminderView =
    httpClient.GET(s"/reminders/$id").responseBodyAs(classOf[ReminderView]).invoke().body()

  // --- T009: SC-001 / SC-002 --------------------------------------------------------------------

  @Test
  def aScheduledReminderIsPendingBeforeItsDelayAndFiredAfter(): Unit =
    val scheduled = schedule("stand up and stretch", delaySeconds = 1)
    assertThat(scheduled.state).isEqualTo("pending")
    assertThat(scheduled.delaySeconds).isEqualTo(1L)

    // SC-001, first half: read immediately — well inside the 1 s delay — and it must NOT have fired.
    // Firing early would be as wrong as never firing (spec US1 scenario 3).
    val early = read(scheduled.reminderId)
    assertThat(early.state).isEqualTo("pending")
    assertThat(early.firedAt.isDefined).isFalse()

    // SC-001, second half: after the delay it has fired, observed rather than argued.
    Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() =>
      assertThat(read(scheduled.reminderId).state).isEqualTo("fired"))
    assertThat(read(scheduled.reminderId).firedAt.isDefined).isTrue()

  @Test
  def aFiredReminderCarriesItsNoteExactly(): Unit =
    // SC-002 / FR-004: inner spacing, punctuation and non-ASCII all survive — only the ends are trimmed,
    // and that happened at validation, before anything was recorded.
    val note = "call  the dentist — ünïcode ✓ \"quoted\""
    val scheduled = schedule(note, delaySeconds = 1)
    Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() =>
      assertThat(read(scheduled.reminderId).state).isEqualTo("fired"))
    assertThat(read(scheduled.reminderId).note).isEqualTo(note)

  @Test
  def anUnknownReminderIs404(): Unit =
    val response = httpClient.GET("/reminders/never-existed").invoke()
    assertThat(response.status()).isEqualTo(StatusCodes.NOT_FOUND)

  // --- T010: FR-007 / SC-005 — rejected, AND nothing scheduled -----------------------------------

  /** "Rejected" and "rejected without side effects" are different claims; this checks both: the `400`
    * carries the domain's exact message, and the store is exactly as it was before the request. */
  private def assertRejectedWithNothingScheduled(json: String, expectedMessage: String): Unit =
    val before = ReminderStore.ids
    val response = postRaw(json)
    assertThat(response.status()).isEqualTo(StatusCodes.BAD_REQUEST)
    assertThat(response.body().utf8String).isEqualTo(expectedMessage)
    assertThat(ReminderStore.ids.toList.sorted.mkString(",")).isEqualTo(before.toList.sorted.mkString(","))

  @Test
  def aBlankOrAbsentNoteIsRejected(): Unit =
    assertRejectedWithNothingScheduled("""{"note":"   ","delaySeconds":60}""", ReminderRequest.BlankNoteMessage)
    assertRejectedWithNothingScheduled("""{"delaySeconds":60}""", ReminderRequest.BlankNoteMessage)

  @Test
  def aDelayOutsideTheSupportedRangeIsRejected(): Unit =
    // Zero and negative are invalid input, not "fire immediately" (spec Edge Cases).
    for delay <- List("0", "-1", "86401") do
      assertRejectedWithNothingScheduled(s"""{"note":"ok","delaySeconds":$delay}""", ReminderRequest.DelayOutOfRangeMessage)
    assertRejectedWithNothingScheduled("""{"note":"ok"}""", ReminderRequest.DelayOutOfRangeMessage)

  @Test
  def malformedJsonIsRejectedByTheSdkWithNothingScheduled(): Unit =
    val before = ReminderStore.ids
    assertThat(postRaw("""{"note": oops""").status()).isEqualTo(StatusCodes.BAD_REQUEST)
    assertThat(ReminderStore.ids.size).isEqualTo(before.size)
