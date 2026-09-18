package com.gwgs.akkaagentic.reminders.api

import akka.http.javadsl.model.HttpResponse
import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.http.{Get, HttpEndpoint}
import akka.javasdk.http.HttpResponses
import com.fasterxml.jackson.annotation.JsonInclude
import com.gwgs.akkaagentic.reminders.application.ReminderStore
import com.gwgs.akkaagentic.reminders.domain.Reminder

/** `GET /reminders/{id}` — the read side, in Scala.
  *
  * It sits in a different file, and a different language, from `POST /reminders` on purpose. Reading
  * a reminder involves no method reference at all, and cancelling one (`DELETE`, added in Phase 4) is
  * keyed on a plain string (specs/017 research Q-B). Only *scheduling* needs Java. Splitting the
  * surface by language makes that finding visible in the file tree rather than only in prose (D7).
  */
@HttpEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class ReminderEndpoint:
  import ReminderEndpoint.*

  @Get("/reminders/{id}")
  def get(id: String): HttpResponse =
    ReminderStore.get(id) match
      case Some(reminder) => HttpResponses.ok(ReminderView.of(reminder))
      // After a restart this is the ACCURATE answer: the timer did not survive either (research Q-C),
      // so reporting a stale `pending` would promise a firing that can no longer happen.
      case None => HttpResponses.notFound(s"no reminder with id $id")

object ReminderEndpoint:

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
