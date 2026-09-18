package com.gwgs.akkaagentic.reminders.application

import akka.javasdk.annotations.Component
import akka.javasdk.timedaction.TimedAction
import org.slf4j.LoggerFactory

/** Capability 15's scheduled work: what happens when a reminder's delay elapses.
  *
  * A `TimedAction` is the SDK family this project has never built. Note two things visible in the base
  * class itself, both of which shape the research:
  *   - it exposes `timers()`, so an action can schedule and delete timers **itself**; and
  *   - its handler returns `TimedAction.Effect`, built by `effects()` — no `Class`/`String` keying in
  *     sight, so **authoring** one looks Scala-clean. The open question (Q-A) is whether anything can
  *     *schedule* it from Scala, which is a property of the **client**, not of this class.
  *
  * The `fail` path exists for Q-D: the SDK's own guidance warns that a failing timed action can
  * reschedule for ever, and FR-008 forbids shipping that. The retry contract has to be measured before
  * it can be bounded, and measuring it needs an action that reliably fails.
  */
@Component(id = "reminder-action")
class ReminderAction extends TimedAction:
  private val logger = LoggerFactory.getLogger(classOf[ReminderAction])

  /** Fire a reminder: record it, so a probe can observe that the timer really elapsed. */
  def fire(note: String): TimedAction.Effect =
    val count = ReminderLog.record(ReminderAction.nameOf(note), note)
    logger.info("reminder fired: note=[{}] firing #{}", note, count)
    effects().done()

  /** Always fails — the Q-D instrument. Every attempt is recorded first, so the probe can COUNT how
    * many times the runtime retried rather than infer it from the docs' warning. */
  def failAlways(note: String): TimedAction.Effect =
    val count = ReminderLog.record(ReminderAction.nameOf(note), note)
    logger.info("reminder deliberately failing: note=[{}] attempt #{}", note, count)
    throw new RuntimeException(s"deliberate failure for [$note], attempt $count")

object ReminderAction:
  /** The probe keys its log by the note, so a test can schedule uniquely-named work without needing
    * the timer name plumbed back through the runtime. */
  def nameOf(note: String): String = note
