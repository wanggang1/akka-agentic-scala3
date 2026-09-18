package com.gwgs.akkaagentic.reminders.domain

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** A reminder a caller asked for, once it is known to be well-formed.
  *
  * Parse, don't validate: [[ReminderRequest.validate]] is the only way to obtain one, so anything
  * holding a `ReminderRequest` has a non-blank note within the length bound and a delay inside the
  * supported range — and never re-checks.
  *
  * **No Akka import** (Constitution II). The rule is deliberately not shared with capability 8's or
  * capability 14's question types: the fields differ, and reusing one of those would put a
  * capability-15 edit one step from another capability's sources, which FR-010 forbids.
  *
  * **Consumed from Java.** The scheduling endpoint is Java by force — producing a `DeferredCall`
  * needs a Java method reference (specs/017 research Q-A) — so this idiomatic `Option`/`Either` API
  * is read through Scala's static forwarders, the shape capability 14 measured (README §16). The
  * domain keeps its Scala form and the caller pays two casts at the boundary; moving the rule into
  * Java to avoid them would grow the quarantine the wall forced.
  */
final case class ReminderRequest(note: String, delay: FiniteDuration)

object ReminderRequest:

  /** The shortest delay this capability accepts.
    *
    * Measured: scheduling carries ~100 ms of overhead (research Q-E), so below a second the requested
    * delay stops being the thing that decides when the reminder fires. Rejecting is honest; silently
    * firing ~100 ms late is not.
    */
  val MinDelay: FiniteDuration = 1.second

  /** The longest delay this capability accepts.
    *
    * A sandbox bound, and a promise-keeping one: a pending timer does **not** survive a restart
    * (research Q-C), so accepting a 30-day reminder would be accepting work the runtime cannot be
    * relied on to do.
    */
  val MaxDelay: FiniteDuration = 24.hours

  /** The longest note carried through firing unaltered (FR-004). */
  val MaxNoteLength: Int = 500

  /** The exact `400` texts, shared so the endpoint, the contract and the tests cannot drift apart. */
  val BlankNoteMessage: String = "note must not be blank"
  val NoteTooLongMessage: String = s"note must be at most $MaxNoteLength characters"
  val DelayOutOfRangeMessage: String =
    s"delaySeconds must be between ${MinDelay.toSeconds} and ${MaxDelay.toSeconds}"

  /** Validate an inbound reminder. Absent, blank or whitespace-only notes are rejected; a present
    * note is trimmed at the ends only, because inner spacing is the caller's content. The delay is
    * given in whole seconds, which is the unit the HTTP contract speaks.
    *
    * Both fields are checked in a fixed order (note, then delay) so a caller sending two bad fields
    * always gets the same message rather than one that depends on evaluation order.
    */
  def validate(note: Option[String], delaySeconds: Option[Int]): Either[String, ReminderRequest] =
    for
      validNote  <- validateNote(note)
      validDelay <- validateDelay(delaySeconds)
    yield ReminderRequest(validNote, validDelay)

  private def validateNote(note: Option[String]): Either[String, String] =
    note.map(_.trim).filter(_.nonEmpty) match
      case None                                     => Left(BlankNoteMessage)
      case Some(text) if text.length > MaxNoteLength => Left(NoteTooLongMessage)
      case Some(text)                               => Right(text)

  private def validateDelay(delaySeconds: Option[Int]): Either[String, FiniteDuration] =
    delaySeconds
      .map(_.toLong)
      .filter(s => s >= MinDelay.toSeconds && s <= MaxDelay.toSeconds)
      .map(FiniteDuration(_, java.util.concurrent.TimeUnit.SECONDS))
      .toRight(DelayOutOfRangeMessage)
