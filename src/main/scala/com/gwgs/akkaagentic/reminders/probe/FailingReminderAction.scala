package com.gwgs.akkaagentic.reminders.probe

import java.util.concurrent.atomic.AtomicReference

import akka.javasdk.annotations.Component
import akka.javasdk.timedaction.TimedAction
import com.gwgs.akkaagentic.reminders.application.{ReminderSettings, ReminderStore}
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

/** The FR-008 instrument: work that fails on every attempt, so the retry bound can be **counted**.
  *
  * Moved out of `ReminderAction` so the production action has exactly one handler, and kept in
  * `probe/` so it stays visibly an instrument.
  *
  * **How it ends.** Research Q-D measured two things that together decide this: the three-argument
  * `createSingleTimer` retries indefinitely, and a timer that exhausts its `maxRetries` stops silently —
  * nothing records that the work never succeeded. And the SDK gives a timed action no attempt number.
  * So the action counts its own attempts in the store and, on the **last permitted one**, records the
  * reminder as `failed` and returns `done()` instead of throwing. That is AGENTS.md's "handle errors in
  * timed actions to avoid infinite rescheduling", made concrete: the bound is enforced here, and the
  * timer's `maxRetries` is a backstop rather than the only line.
  *
  * Earlier attempts throw, because a retry is exactly what they are asking the runtime for.
  */
@Component(id = "failing-reminder-action")
class FailingReminderAction(config: Config) extends TimedAction:
  private val logger = LoggerFactory.getLogger(classOf[FailingReminderAction])
  private val limit = ReminderSettings.maxRetries(config)

  def fail(reminderId: String): TimedAction.Effect =
    FailingReminderAction.witness(reminderId)
    val attempt = ReminderStore.get(reminderId).map(_.attempts + 1).getOrElse(1)
    if attempt >= limit then
      ReminderStore.markFailed(reminderId, s"the work failed on all $limit permitted attempts")
      logger.warn("reminder [{}] failed on its last permitted attempt ({} of {}); stopping", reminderId, attempt, limit)
      effects().done()
    else
      ReminderStore.recordAttempt(reminderId)
      logger.info("reminder [{}] deliberately failing, attempt {} of {}", reminderId, attempt, limit)
      throw RuntimeException(s"deliberate failure for [$reminderId], attempt $attempt of $limit")

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
