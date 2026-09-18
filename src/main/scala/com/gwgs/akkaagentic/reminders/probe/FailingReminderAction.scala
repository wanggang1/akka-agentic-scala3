package com.gwgs.akkaagentic.reminders.probe

import akka.javasdk.annotations.Component
import akka.javasdk.timedaction.TimedAction
import com.gwgs.akkaagentic.reminders.application.ReminderStore
import org.slf4j.LoggerFactory

/** The FR-008 instrument: a timed action that always fails, so the retry contract can be **counted**.
  *
  * Moved out of `ReminderAction` so the production action has exactly one handler and the instrument
  * stays visibly an instrument. The SDK's guidance warns that a failing timed action can reschedule
  * for ever, and research Q-D measured that it does with the 3-argument overload — so bounding it has
  * to be demonstrated by count against something that reliably fails.
  */
@Component(id = "failing-reminder-action")
class FailingReminderAction extends TimedAction:
  private val logger = LoggerFactory.getLogger(classOf[FailingReminderAction])

  def fail(reminderId: String): TimedAction.Effect =
    val attempts = ReminderStore.recordAttempt(reminderId).map(_.attempts).getOrElse(0)
    logger.info("reminder [{}] deliberately failing, attempt #{}", reminderId, attempts)
    throw RuntimeException(s"deliberate failure for [$reminderId], attempt $attempts")
