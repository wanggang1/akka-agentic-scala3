package com.gwgs.akkaagentic.feed.probe

import java.util.concurrent.atomic.AtomicReference

/** Phase 0 probe instrument for capability 16 (specs/018): what the probe consumers actually received,
  * and the switches a test uses to make a delivery fail.
  *
  * **Not the capability's design.** It exists so the probes can answer Q-A–Q-G by measurement before
  * anything is decided. One atomic cell over an immutable value, changed by pure functions — the pattern
  * from capability 15's review — because consumers are constructed per message, so the only place an
  * observation can outlive one delivery is shared state.
  */
object ProbeLog:

  /** One thing a probe consumer saw. `attempt` counts deliveries of the same (consumer, subject, detail). */
  final case class Observation(
      consumer: String,
      subject: Option[String],
      kind: String,
      detail: String,
      metadataKeys: List[String],
      attempt: Int,
      atMillis: Long)

  /** Which subjects fail, and after how many attempts a failing subject gives up (Int.MaxValue = never). */
  final case class Switches(poisoned: Set[String] = Set.empty, giveUpAfter: Int = Int.MaxValue)

  final case class State(observations: Vector[Observation] = Vector.empty, switches: Switches = Switches())

  private val cell = AtomicReference(State())

  def record(consumer: String, subject: Option[String], kind: String, detail: String, metadataKeys: List[String]): Observation =
    val at = System.currentTimeMillis()
    cell.updateAndGet { state =>
      val attempt = state.observations.count(o => o.consumer == consumer && o.subject == subject && o.detail == detail) + 1
      state.copy(observations = state.observations :+ Observation(consumer, subject, kind, detail, metadataKeys, attempt, at))
    }.observations.last

  def poison(subject: String): Unit = cell.updateAndGet(s => s.copy(switches = s.switches.copy(poisoned = s.switches.poisoned + subject)))
  def cure(subject: String): Unit = cell.updateAndGet(s => s.copy(switches = s.switches.copy(poisoned = s.switches.poisoned - subject)))
  def giveUpAfter(n: Int): Unit = cell.updateAndGet(s => s.copy(switches = s.switches.copy(giveUpAfter = n)))
  def switches: Switches = cell.get().switches

  def observations: Vector[Observation] = cell.get().observations
  def of(consumer: String, subject: String): Vector[Observation] =
    observations.filter(o => o.consumer == consumer && o.subject.contains(subject))

  def clear(): Unit = cell.set(State())
