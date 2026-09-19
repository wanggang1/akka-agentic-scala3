package com.gwgs.akkaagentic.reminders.probe

import java.time.Duration

import akka.javasdk.client.ComponentClient
import akka.javasdk.timer.TimerScheduler
import com.gwgs.akkaagentic.reminders.application.ReminderAction

/** Q-A probe, attempt 1: can a **Scala** caller produce the `DeferredCall` a timer needs?
  *
  * **What this file is evidence OF — and what it is not.** It is the **compile-time** half of Q-A: it
  * shows that the documented scheduling form, written as a Scala lambda, *compiles*, which is precisely
  * what makes the wall easy to walk into. **Nothing executes this class.** It is not a component, is not
  * in the descriptor, and no test or endpoint calls it.
  *
  * The **run-time** half — the failure quoted in research Q-A and asserted by
  * `TimedActionProbeIntegrationTest` — comes from the same lambda inlined in
  * `ReminderProbeEndpoint.scalaSchedule`, which is why that diagnostic names
  * `ReminderProbeEndpoint::$anonfun$1` and not this class. The two are kept deliberately separate:
  * routing the endpoint through this class would change the class the diagnostic names, and the quoted
  * evidence with it. (Kept per FR-013 after review, 2026-09-18, with this note so no reader mistakes it
  * for the executed evidence.)
  *
  * `TimerScheduler.createSingleTimer(name, delay, maxRetries, deferred)` is keyed on values — the wall,
  * if any, is in producing the `deferred` argument. `TimedActionClient` offers only
  * `method(japi.Function|Function2)`, and `dynamicCall` (the project's escape hatch) returns a
  * `DynamicMethodRef`, which has no `deferred()` at all — the same shape that denied capability 14 a
  * `source()`.
  */
class ScalaScheduleAttempt(componentClient: ComponentClient, timers: TimerScheduler):

  /** The documented form, written as a Scala lambda. */
  def scheduleViaLambda(timerName: String, note: String, delay: Duration): Unit =
    val deferred = componentClient
      .forTimedAction()
      .method[ReminderAction, String, Any]((action, n) => action.fire(n))
      .deferred(note)
    // Four-argument, like everything in this capability (FR-008); never reached — the wall is in
    // `deferred()` above.
    timers.createSingleTimer(timerName, delay, 1, deferred)

  /** Cancelling — Q-B. Keyed on a plain string, so this should need nothing from the wall. */
  def cancel(timerName: String): Unit = timers.delete(timerName)
