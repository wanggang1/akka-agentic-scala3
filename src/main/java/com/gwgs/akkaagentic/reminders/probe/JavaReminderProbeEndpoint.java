package com.gwgs.akkaagentic.reminders.probe;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import akka.javasdk.timer.TimerScheduler;
import com.gwgs.akkaagentic.reminders.application.ReminderAction;

import java.time.Duration;

/**
 * Phase 0 probe surface, Java side — the <strong>control</strong>.
 *
 * <p>If the Scala route fails and this one works, the finding is about Scala rather than about our
 * usage. That control is what made capability 14's headline safe to publish, and the same discipline
 * applies here.
 *
 * <p>Note what this class does that the Scala one cannot (if Q-A resolves as the jar suggests): it
 * holds a Java <em>method reference</em>, {@code ReminderAction::fire}, which is the only way to reach
 * {@code ComponentDeferredMethodRef.deferred()}. The action it points at is <strong>Scala</strong>.
 */
@HttpEndpoint
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class JavaReminderProbeEndpoint {

  private final ComponentClient componentClient;
  private final TimerScheduler timers;

  public JavaReminderProbeEndpoint(ComponentClient componentClient, TimerScheduler timers) {
    this.componentClient = componentClient;
    this.timers = timers;
  }

  /** Q-A control: schedule the Scala action from Java, via a method reference. */
  @Post("/probe/java-schedule/{name}/{ms}")
  public HttpResponse javaSchedule(String name, Integer ms) {
    try {
      var deferred = componentClient
          .forTimedAction()
          .method(ReminderAction::fire)
          .deferred(name);
      timers.createSingleTimer(name, Duration.ofMillis(ms), deferred);
      return HttpResponses.ok("SCHEDULED");
    } catch (RuntimeException e) {
      return HttpResponses.ok("FAILED: " + e.getClass().getName() + ": " + e.getMessage());
    }
  }

  /**
   * Q-D: schedule work that always fails, so the retry contract can be COUNTED rather than inferred.
   * {@code maxRetries} exercises the four-argument overload; the test compares it against the
   * three-argument default.
   */
  @Post("/probe/java-schedule-failing/{name}/{ms}/{maxRetries}")
  public HttpResponse javaScheduleFailing(String name, Integer ms, Integer maxRetries) {
    try {
      var deferred = componentClient
          .forTimedAction()
          .method(ReminderAction::failAlways)
          .deferred(name);
      if (maxRetries < 0) {
        timers.createSingleTimer(name, Duration.ofMillis(ms), deferred);
      } else {
        timers.createSingleTimer(name, Duration.ofMillis(ms), maxRetries, deferred);
      }
      return HttpResponses.ok("SCHEDULED");
    } catch (RuntimeException e) {
      return HttpResponses.ok("FAILED: " + e.getClass().getName() + ": " + e.getMessage());
    }
  }
}
