package com.gwgs.akkaagentic.reminders.application

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters.*

/** Phase 0 probe instrument: an in-process record of what actually fired.
  *
  * **Not the capability's storage design.** It exists so a probe can answer "did the timer fire, how
  * many times, and with what note?" without first committing to where a reminder's state should live —
  * that decision depends on Q-A/Q-B/Q-C, which have not run yet. Deciding storage before the
  * measurement would be exactly the mistake capabilities 12–14 avoided by probing first.
  *
  * Deliberately an `object` with static-backed state: the runtime constructs components per call, so a
  * per-instance field would record nothing observable from a test.
  */
object ReminderLog:

  private val firings = ConcurrentHashMap[String, AtomicInteger]()
  private val notes = ConcurrentHashMap[String, String]()

  def record(name: String, note: String): Int =
    notes.put(name, note)
    firings.computeIfAbsent(name, _ => AtomicInteger(0)).incrementAndGet()

  def timesFired(name: String): Int = Option(firings.get(name)).map(_.get).getOrElse(0)
  def noteFor(name: String): Option[String] = Option(notes.get(name))
  def fired: Set[String] = firings.keySet.asScala.toSet
  def clear(): Unit = { firings.clear(); notes.clear() }
