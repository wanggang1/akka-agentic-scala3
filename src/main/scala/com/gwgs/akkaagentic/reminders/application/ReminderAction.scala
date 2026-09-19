package com.gwgs.akkaagentic.reminders.application

import akka.javasdk.annotations.Component
import akka.javasdk.timedaction.TimedAction
import com.gwgs.akkaagentic.reminders.application.BoundedAttempts.Outcome
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

/** What happens when a reminder's delay elapses: it is marked fired.
  *
  * **Authoring a timed action is Scala-clean.** The handler returns `TimedAction.Effect` built by
  * `effects()` — no method reference, no `Class`/`String` keying. What cannot be done from Scala is
  * *scheduling* it (specs/017 research Q-A), which is why the caller that does is the capability's one
  * Java class, and why this class is not.
  *
  * **The work runs under a bound this action enforces itself** (`BoundedAttempts`). If it ever throws,
  * the attempt is counted and the runtime asked to retry; on the last permitted attempt the reminder is
  * recorded `failed` and the action stops. Without that, an exhausted timer would stop silently and the
  * reminder would read `pending` for ever. (Added in PR review: this pattern was first applied only to
  * the always-failing instrument.)
  *
  * **A reminder it cannot fire is not a failure.** Unknown, or already cancelled — the cancel won the
  * race at the store (see `Reminders`) — are *outcomes*: the work returns normally and nothing is
  * retried, since retrying would repeat a decision that has correctly already been made.
  */
@Component(id = "reminder-action")
class ReminderAction(config: Config) extends TimedAction:
  private val logger = LoggerFactory.getLogger(classOf[ReminderAction])
  private val limit = ReminderSettings.maxRetries(config)

  def fire(reminderId: String): TimedAction.Effect =
    BoundedAttempts.run(reminderId, limit)(markFired(reminderId)) match
      case Outcome.Succeeded => effects().done()
      case Outcome.WillRetry(attempt, cause) =>
        logger.warn(s"reminder [$reminderId] failed to fire, attempt $attempt of $limit; retrying", cause)
        throw cause
      case Outcome.GaveUp(attempt, cause) =>
        logger.error(s"reminder [$reminderId] failed on its last permitted attempt ($attempt of $limit)", cause)
        effects().done()

  private def markFired(reminderId: String): Unit =
    ReminderStore.markFired(reminderId) match
      case Some(fired) => logger.info("reminder [{}] fired: note=[{}]", reminderId, fired.note)
      case None =>
        val state = ReminderStore.get(reminderId).map(_.state.label).getOrElse("unknown")
        logger.info("reminder [{}] not fired: it is {}", reminderId, state)
