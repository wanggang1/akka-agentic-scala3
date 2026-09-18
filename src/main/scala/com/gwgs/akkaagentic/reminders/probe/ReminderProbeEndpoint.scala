package com.gwgs.akkaagentic.reminders.probe

import java.time.Duration

import scala.util.{Failure, Success, Try}

import akka.http.javadsl.model.HttpResponse
import akka.javasdk.annotations.Acl
import akka.javasdk.annotations.http.{HttpEndpoint, Post}
import akka.javasdk.client.ComponentClient
import akka.javasdk.http.HttpResponses
import akka.javasdk.timer.TimerScheduler
import com.gwgs.akkaagentic.reminders.application.ReminderAction

/** Phase 0 probe surface, Scala side. Every route answers `200` with a **verdict string** rather than
  * failing, so a test can read what happened instead of only that something went wrong — the shape
  * capabilities 12–14 used for their probes.
  */
@HttpEndpoint
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class ReminderProbeEndpoint(componentClient: ComponentClient, timers: TimerScheduler):

  /** Q-A — schedule from Scala. Compiles (verified); the question is what happens now. */
  @Post("/probe/scala-schedule/{name}/{ms}")
  def scalaSchedule(name: String, ms: Int): HttpResponse =
    HttpResponses.ok(
      Try {
        val deferred = componentClient
          .forTimedAction()
          .method[ReminderAction, String, Any]((action, n) => action.fire(n))
          .deferred(name)
        // Four-argument, like everything in this capability (FR-008). It is never reached — the
        // failure this route exists to record happens one line up, in `deferred()`.
        timers.createSingleTimer(name, Duration.ofMillis(ms.toLong), 1, deferred)
      } match
        case Success(_) => "SCHEDULED"
        case Failure(t) => s"FAILED: ${ReminderProbeEndpoint.chain(t)}"
    )

  /** Q-B — cancel from Scala. `delete` is keyed on a plain string, so nothing here should touch the
    * wall; whether it actually stops a timer that **Java** scheduled is the measurement. */
  @Post("/probe/scala-cancel/{name}")
  def scalaCancel(name: String): HttpResponse =
    HttpResponses.ok(
      Try(timers.delete(name)) match
        case Success(_) => "CANCELLED"
        case Failure(t) => s"FAILED: ${ReminderProbeEndpoint.chain(t)}"
    )

object ReminderProbeEndpoint:
  /** The whole cause chain — capability 13's diagnostic was only legible once unwrapped. */
  def chain(t: Throwable): String =
    Iterator
      .iterate(t)(_.getCause)
      .takeWhile(_ != null)
      .map(e => s"${e.getClass.getName}: ${e.getMessage}")
      .mkString(" <- ")
