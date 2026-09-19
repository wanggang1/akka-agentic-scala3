package com.gwgs.akkaagentic.reminders.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.gwgs.akkaagentic.reminders.domain.Reminder;
import com.gwgs.akkaagentic.reminders.probe.FailingReminderAction;
import com.typesafe.config.ConfigFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * T021 — User Story 3: work that always fails stops after a bounded count, and the reminder ends in
 * a state a caller can see (FR-008, SC-006).
 *
 * <p><b>Why this test is Java.</b> Scheduling the always-failing action needs a {@code
 * FailingReminderAction::fail} method reference — the same measured wall that makes {@code POST
 * /reminders} Java (specs/017 research Q-A). It is a <i>test</i>, so it does not count against the
 * production quarantine (capability 4 and 11 precedent). It schedules through the testkit's own {@code
 * getTimerScheduler()}, with the four-argument overload.
 *
 * <p><b>What this proves about production.</b> The instrument runs its work through {@code
 * BoundedAttempts}, the same helper the production {@code ReminderAction} uses, so the bound shown
 * here is the shipped code path; only the work differs (it always throws).
 *
 * <p><b>What makes the stop attributable.</b> The timer's {@code maxRetries} is set deliberately
 * <i>above</i> the action's own limit. If the action did not stop itself, the runtime would keep
 * going — so a stop at exactly the limit can only be the action's doing, not a coincidence of the
 * runtime's retry accounting (which the SDK does not document and research left open).
 *
 * <p><b>Real-time cost</b>, stated rather than hidden (FR-011): the retries arrive with the runtime's
 * backoff, and proving that nothing <i>more</i> arrives needs a wait longer than that backoff. The
 * wait is calibrated from the gap this run actually observed, not guessed.
 */
public class BoundedRetryIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withAdditionalConfig(
        "akka.javasdk.agent.googleai-gemini.api-key = n/a");
  }

  @BeforeEach
  void reset() {
    ReminderStore.clear();
  }

  private Reminder reminder(String id) {
    return ReminderStore.get(id).get();
  }

  @Test
  public void failingWorkStopsAtItsBoundAndReadsFailed() throws Exception {
    int limit = ReminderSettings.maxRetries(ConfigFactory.load());
    var id = "doomed-" + UUID.randomUUID();
    ReminderStore.record(id, "always fails", Instant.now());

    var deferred =
        componentClient.forTimedAction().method(FailingReminderAction::fail).deferred(id);
    testKit.getTimerScheduler().createSingleTimer(id, Duration.ofMillis(300), limit + 3, deferred);

    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .pollInterval(Duration.ofMillis(20))
        .until(() -> FailingReminderAction.invocationsOf(id) >= 1);
    long firstInvocation = System.nanoTime();

    // It ends FAILED — and says so. Without the action's own handling it would read `pending` for
    // ever, because a timer that exhausts its retries tells nobody (research Q-D).
    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .pollInterval(Duration.ofMillis(20))
        .until(() -> reminder(id).state().label().equals("failed"));
    long failedAt = System.nanoTime();

    assertThat(reminder(id).attempts()).isEqualTo(limit);
    assertThat(reminder(id).failure().isDefined()).isTrue();
    assertThat(reminder(id).firedAt().isDefined()).isFalse();

    // It STOPPED — shown by count, with an independent witness the store cannot provide (a failed
    // reminder ignores later transitions, so a stray retry would leave no trace there). The wait is
    // three times the mean gap this run observed between attempts, because the runtime's backoff
    // widens (research Q-D: 2, 3, 3, 3, 4, 4 attempts at 5 s intervals on the unbounded form).
    long meanGapMs = (failedAt - firstInvocation) / 1_000_000 / Math.max(1, limit - 1);
    long settleMs = Math.max(3_000, 3 * meanGapMs);
    Thread.sleep(settleMs);

    assertThat(FailingReminderAction.invocationsOf(id)).isEqualTo(limit);
    assertThat(reminder(id).state().label()).isEqualTo("failed");
    System.out.printf(
        "T021 >>> limit=%d invocations=%d meanGap=%dms settled=%dms%n",
        limit, FailingReminderAction.invocationsOf(id), meanGapMs, settleMs);
  }
}
