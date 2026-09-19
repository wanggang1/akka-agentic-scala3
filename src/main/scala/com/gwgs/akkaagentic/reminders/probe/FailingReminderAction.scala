package com.gwgs.akkaagentic.reminders.probe

import java.util.concurrent.atomic.AtomicReference

import akka.javasdk.annotations.Component
import akka.javasdk.timedaction.TimedAction
import com.gwgs.akkaagentic.reminders.application.BoundedAttempts.Outcome
import com.gwgs.akkaagentic.reminders.application.{BoundedAttempts, ReminderSettings}
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

/** The FR-008 instrument: work that fails on every attempt, so the retry bound can be **counted**.
  *
  * Kept in `probe/` so it stays visibly an instrument. It runs its work through `BoundedAttempts` —
  * **the same helper the production `ReminderAction` uses** — so the bound `BoundedRetryIntegrationTest`
  * proves here is the production code path, not a copy of it. The only difference is the work itself,
  * which always throws.
  */
@Component(id = "failing-reminder-action")
class FailingReminderAction(config: Config) extends TimedAction:
  private val logger = LoggerFactory.getLogger(classOf[FailingReminderAction])
  private val limit = ReminderSettings.maxRetries(config)

  def fail(reminderId: String): TimedAction.Effect =
    FailingReminderAction.witness(reminderId)
    BoundedAttempts.run(reminderId, limit)(throw RuntimeException(s"deliberate failure for [$reminderId]")) match
      case Outcome.Succeeded => effects().done()
      case Outcome.WillRetry(attempt, cause) =>
        logger.info("reminder [{}] deliberately failing, attempt {} of {}", reminderId, attempt, limit)
        throw cause
      case Outcome.GaveUp(attempt, _) =>
        logger.warn("reminder [{}] failed on its last permitted attempt ({} of {}); stopping", reminderId, attempt, limit)
        effects().done()

object FailingReminderAction:

  /** An **independent witness** of how many times the runtime actually invoked `fail` per reminder.
    *
    * It exists because the store cannot testify to it: once a reminder is `failed`, every further
    * transition is a no-op, so a stray retry after the last attempt would leave no trace there. This
    * counter takes no part in deciding when to stop — which is exactly what makes it a valid check on
    * that decision. One atomic cell over an immutable map, updated by a pure function.
    */
  private val invocations = AtomicReference(Map.empty[String, Int])

  def invocationsOf(reminderId: String): Int = invocations.get().getOrElse(reminderId, 0)

  private def witness(reminderId: String): Unit =
    invocations.updateAndGet(counts => counts.updated(reminderId, counts.getOrElse(reminderId, 0) + 1))
