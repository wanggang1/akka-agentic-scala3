package com.gwgs.akkaagentic.reminders.probe

import java.time.Duration

import akka.javasdk.client.ComponentClient
import akka.javasdk.timer.TimerScheduler
import com.gwgs.akkaagentic.reminders.application.ReminderAction

/** Q-A probe, attempt 1: can a **Scala** caller produce the `DeferredCall` a timer needs?
  *
  * `TimerScheduler.createSingleTimer(name, delay, deferred)` is keyed on values — the wall, if any, is
  * in producing the third argument. `TimedActionClient` offers only `method(japi.Function|Function2)`,
  * and `dynamicCall` (the project's escape hatch) returns a `DynamicMethodRef`, which has no
  * `deferred()` at all — the same shape that denied capability 14 a `source()`.
  *
  * Staged as a plain class, not a component: this is a compile probe first. If it compiles, capability
  * 13 and 14's precedent says the interesting failure is at RUN time.
  */
class ScalaScheduleAttempt(componentClient: ComponentClient, timers: TimerScheduler):

  /** The documented form, written as a Scala lambda. */
  def scheduleViaLambda(timerName: String, note: String, delay: Duration): Unit =
    val deferred = componentClient
      .forTimedAction()
      .method[ReminderAction, String, Any]((action, n) => action.fire(n))
      .deferred(note)
    timers.createSingleTimer(timerName, delay, deferred)

  /** Cancelling — Q-B. Keyed on a plain string, so this should need nothing from the wall. */
  def cancel(timerName: String): Unit = timers.delete(timerName)
