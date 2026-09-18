package com.gwgs.akkaagentic.reminders.application

import akka.javasdk.annotations.Component
import akka.javasdk.timedaction.TimedAction
import org.slf4j.LoggerFactory

/** What happens when a reminder's delay elapses: it is marked fired.
  *
  * **Authoring a timed action is Scala-clean.** The handler returns `TimedAction.Effect` built by
  * `effects()` — no method reference, no `Class`/`String` keying. What cannot be done from Scala is
  * *scheduling* it (specs/017 research Q-A), which is why the caller that does is the capability's one
  * Java class, and why this class is not.
  *
  * **It never throws for a reminder it cannot fire.** A reminder may be unknown here, or already
  * cancelled — the cancel won the race at the store (see `Reminders`). Both are *outcomes*, not
  * failures: throwing would make the runtime retry work that has correctly already been decided,
  * which is exactly the loop FR-008 exists to prevent.
  */
@Component(id = "reminder-action")
class ReminderAction extends TimedAction:
  private val logger = LoggerFactory.getLogger(classOf[ReminderAction])

  def fire(reminderId: String): TimedAction.Effect =
    ReminderStore.markFired(reminderId) match
      case Some(fired) => logger.info("reminder [{}] fired: note=[{}]", reminderId, fired.note)
      case None =>
        val state = ReminderStore.get(reminderId).map(_.state.label).getOrElse("unknown")
        logger.info("reminder [{}] not fired: it is {}", reminderId, state)
    effects().done()
