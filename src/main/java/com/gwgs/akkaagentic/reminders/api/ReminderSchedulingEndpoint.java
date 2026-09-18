package com.gwgs.akkaagentic.reminders.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import akka.javasdk.timer.TimerScheduler;
import com.gwgs.akkaagentic.reminders.application.ReminderAction;
import com.gwgs.akkaagentic.reminders.application.ReminderStore;
import com.gwgs.akkaagentic.reminders.domain.ReminderRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import scala.Option;
import scala.util.Either;
import scala.util.Left;
import scala.util.Right;

/**
 * {@code POST /reminders} — the capability's only Java class, and the only place a reminder is
 * scheduled.
 *
 * <p><b>Why Java, measured rather than assumed</b> (specs/017 research Q-A). Scheduling needs a {@code
 * DeferredCall}, and the only way to produce one is {@code
 * componentClient.forTimedAction().method(ReminderAction::fire).deferred(...)} — a Java method
 * reference. {@code TimedActionClient} has no id-keyed form, and the {@code dynamicCall} escape hatch
 * has no {@code deferred()}. The Scala lambda form compiles and then fails at run time; that attempt is
 * kept as evidence in {@code probe/ScalaScheduleAttempt.scala}. Reading and cancelling are
 * Scala-clean, so they live in {@code ReminderEndpoint.scala}: the wall runs through this family by
 * <i>operation</i>, and this file is where it runs.
 *
 * <p><b>Everything else stays Scala.</b> The validation rule, the store and the action this schedules
 * are all Scala, read from here through Scala's static forwarders (the capability-14 shape). This class
 * only holds the method reference and the HTTP shape around it.
 */
@HttpEndpoint
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class ReminderSchedulingEndpoint {

  /**
   * Retries a failing firing gets before the reminder is abandoned. Always passed explicitly: the
   * three-argument {@code createSingleTimer} was measured still retrying at 30 seconds (research Q-D),
   * so FR-008 forbids relying on its default. Becomes configurable in T025.
   */
  static final int MAX_RETRIES = 2;

  public record ScheduleRequest(String note, Integer delaySeconds) {}

  public record ScheduledReminder(
      String reminderId, String note, long delaySeconds, String state) {}

  private final ComponentClient componentClient;
  private final TimerScheduler timers;

  public ReminderSchedulingEndpoint(ComponentClient componentClient, TimerScheduler timers) {
    this.componentClient = componentClient;
    this.timers = timers;
  }

  @Post("/reminders")
  public HttpResponse schedule(ScheduleRequest request) {
    // Validation first, before anything is recorded or scheduled (FR-007). `Option.apply` turns the
    // wire's nullables into None at the boundary, so the domain never sees null. The `(Object)` cast
    // is the one wrinkle beyond capability 14's: Scala's `Option[Int]` erases to `Option<Object>` in
    // Java's view, so an `Option<Integer>` is not assignable to it.
    Either<String, ReminderRequest> validated =
        ReminderRequest.validate(
            Option.apply(request == null ? null : request.note()),
            Option.apply((Object) (request == null ? null : request.delaySeconds())));
    if (validated.isLeft()) {
      return HttpResponses.badRequest(((Left<String, ReminderRequest>) validated).value());
    }
    var valid = ((Right<String, ReminderRequest>) validated).value();

    // Recorded BEFORE scheduling, so the action can never fire against an id the store has not seen.
    var reminderId = UUID.randomUUID().toString();
    var pending = ReminderStore.record(reminderId, valid.note(), Instant.now());

    try {
      var deferred =
          componentClient.forTimedAction().method(ReminderAction::fire).deferred(reminderId);
      // The FOUR-argument overload, always (FR-008). The timer is named by the reminder id, which is
      // what lets the Scala side cancel it with nothing but that string.
      timers.createSingleTimer(
          reminderId, Duration.ofSeconds(valid.delay().toSeconds()), MAX_RETRIES, deferred);
    } catch (RuntimeException e) {
      // Nothing will fire, so the record must not keep claiming `pending`.
      ReminderStore.markFailed(reminderId, "could not be scheduled: " + e.getMessage());
      throw e;
    }

    return HttpResponses.created(
        new ScheduledReminder(
            reminderId, pending.note(), valid.delay().toSeconds(), pending.state().label()),
        "/reminders/" + reminderId);
  }
}
