package com.gwgs.akkaagentic.reminders.api

import java.nio.charset.StandardCharsets.UTF_8

import scala.util.{Failure, Success, Try}

import akka.http.javadsl.model.{ContentTypes, HttpResponse, StatusCodes}
import akka.javasdk.JsonSupport
import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.http.{Delete, Get, HttpEndpoint}
import akka.javasdk.http.HttpResponses
import akka.javasdk.timer.TimerScheduler
import com.fasterxml.jackson.annotation.JsonInclude
import com.gwgs.akkaagentic.reminders.application.ReminderStore
import com.gwgs.akkaagentic.reminders.domain.{CancelOutcome, Reminder}
import org.slf4j.LoggerFactory

/** `GET` and `DELETE /reminders/{id}` — the read and cancel side, in Scala.
  *
  * It sits in a different file, and a different language, from `POST /reminders` on purpose. Reading
  * a reminder involves no method reference at all, and cancelling one is keyed on a plain string —
  * `TimerScheduler.delete(String)`, measured working from Scala against a Java-scheduled timer
  * (specs/017 research Q-B). Only *scheduling* needs Java. Splitting the surface by language makes
  * that finding visible in the file tree rather than only in prose (D7): **you can cancel, from here,
  * what you could not have scheduled from here.**
  */
@HttpEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class ReminderEndpoint(timers: TimerScheduler):
  import ReminderEndpoint.*

  private val logger = LoggerFactory.getLogger(classOf[ReminderEndpoint])

  @Get("/reminders/{id}")
  def get(id: String): HttpResponse =
    ReminderStore.get(id) match
      case Some(reminder) => HttpResponses.ok(ReminderView.of(reminder))
      // After a restart this is the ACCURATE answer: the timer did not survive either (research Q-C),
      // so reporting a stale `pending` would promise a firing that can no longer happen.
      case None => HttpResponses.notFound(s"no reminder with id $id")

  /** Cancel a reminder that has not fired. Three outcomes, kept distinct (FR-006): `200` it was
    * pending and now will not fire; `409` there was nothing to cancel, with the state it is actually in;
    * `404` no such reminder. Answering "cancelled" to a request that cancelled nothing is the specific
    * error this exists to prevent.
    *
    * **The fire-versus-cancel window, decided (T019).** The store is the arbiter, not the timer. The
    * cancel is committed there *first*, atomically, and only then is the timer deleted:
    *   - if the cancel commits first, a firing that is already under way finds the reminder cancelled
    *     and does nothing (`ReminderAction.fire` never overwrites a terminal state) — so "cancelled"
    *     stays true even if the timer could not be stopped in time;
    *   - if the firing commits first, the cancel is told the reminder has `fired` and answers `409`.
    *
    * Either way the caller is told what actually happened. Deleting the timer is then an optimisation
    * — it spares the runtime a firing that would be a no-op — which is why a failure to delete it is
    * logged rather than reported: it cannot make the answer untrue.
    */
  @Delete("/reminders/{id}")
  def cancel(id: String): HttpResponse =
    ReminderStore.cancel(id) match
      case CancelOutcome.Cancelled(reminder) =>
        Try(timers.delete(id)) match
          case Success(_) => ()
          case Failure(error) =>
            logger.warn(s"reminder [$id] cancelled in the store, but its timer could not be deleted", error)
        HttpResponses.ok(CancelReply.of(reminder))
      case CancelOutcome.AlreadyTerminal(reminder) => conflict(CancelReply.of(reminder))
      case CancelOutcome.Unknown                   => HttpResponses.notFound(s"no reminder with id $id")

object ReminderEndpoint:

  /** The answer to a cancel: which reminder, and the state it is in now. On a `409` that state is the
    * one that stood — `fired`, `failed` or `cancelled` — never the one the caller hoped for. */
  final case class CancelReply(reminderId: String, state: String)

  object CancelReply:
    def of(reminder: Reminder): CancelReply = CancelReply(reminder.id, reminder.state.label)

  /** `HttpResponses` has no JSON-bodied `409` helper, so the body is encoded with the same Scala-aware
    * mapper every other endpoint body uses. */
  private def conflict(body: CancelReply): HttpResponse =
    HttpResponses.of(StatusCodes.CONFLICT, ContentTypes.APPLICATION_JSON, JsonSupport.encodeToString(body).getBytes(UTF_8))

  /** The wire shape. `NON_ABSENT` omits empty `Option`s, so each timestamp appears only on the state it
    * describes — `firedAt` only on `fired`, and so on — rather than as a `null` a caller must interpret.
    * An HTTP body, so it goes through the Scala-aware mapper and stays idiomatic (README §3). */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  final case class ReminderView(
      reminderId: String,
      note: String,
      state: String,
      firedAt: Option[String] = None,
      cancelledAt: Option[String] = None,
      failure: Option[String] = None)

  object ReminderView:
    def of(reminder: Reminder): ReminderView =
      ReminderView(
        reminderId = reminder.id,
        note = reminder.note,
        state = reminder.state.label,
        firedAt = reminder.firedAt.map(_.toString),
        cancelledAt = reminder.cancelledAt.map(_.toString),
        failure = reminder.failure)
