package com.gwgs.akkaagentic.feed.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.gwgs.akkaagentic.a2a.application.TodoEntity;
import com.gwgs.akkaagentic.feed.domain.ActivityEntry;
import com.gwgs.akkaagentic.feed.domain.SetAside;
import com.gwgs.akkaagentic.feed.probe.ProbeLog;
import com.typesafe.config.ConfigFactory;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.jdk.javaapi.CollectionConverters;

/**
 * T020 / T021 — User Story 2 on the <b>real projection path</b>: a delivery that cannot be processed is set
 * aside after a bounded number of attempts, and every other user keeps flowing.
 *
 * <p><b>Why here and not on the mocked channel.</b> The TestKit's key-value mock does not redeliver a
 * failing message and loses the ones published around it (specs/018 research Q-F), so a failure assertion
 * there would pass without exercising anything. On the real path the measured behaviour is the opposite and
 * far worse: redelivery is unbounded (ms 0, 277, 789, 1719, 3419, 6985, 13976, 27611 …) and it holds up
 * <i>every other user</i> until it stops failing.
 *
 * <p><b>Why Java.</b> Writing capability 6's {@code TodoEntity} needs a {@code TodoEntity::add} method
 * reference. It is a test, so it does not count against the capability's "no Java in production" claim.
 */
public class BoundedActivityDeliveryIntegrationTest extends TestKitSupport {

  private static final Logger logger = LoggerFactory.getLogger(BoundedActivityDeliveryIntegrationTest.class);

  private final int attemptLimit = FeedSettings.maxAttempts(ConfigFactory.load());

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a");
  }

  @BeforeEach
  void reset() {
    ProbeLog.clear();
    ActivityStore.clear();
  }

  private void add(String user, String description) {
    componentClient.forKeyValueEntity(user).method(TodoEntity::add).invoke(description);
  }

  /** Deliveries the runtime actually made — the witness, which the store cannot provide: once a delivery is
   * set aside its attempt count is cleared. */
  private int deliveriesTo(String user) {
    return CollectionConverters.asJava(ProbeLog.of("todo", user)).size();
  }

  private List<SetAside> setAsidesFor(String user) {
    return CollectionConverters.asJava(ActivityStore.feed().setAsides()).stream()
        .filter(a -> a.username().equals(user))
        .toList();
  }

  private List<ActivityEntry> entriesFor(String user) {
    return CollectionConverters.asJava(ActivityStore.feed().entriesFor(user));
  }

  @Test
  public void aDeliveryThatKeepsFailingIsSetAsideAndTheStreamKeepsMoving() {
    ProbeLog.poison("poison-user");
    add("poison-user", "doomed");

    // Bounded: redelivery stops once the handler gives up instead of throwing.
    Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> !setAsidesFor("poison-user").isEmpty());
    // Checked the moment the set-aside appears. A set-aside is NOT a tombstone: because the stream
    // restarts while failing, the same message can be replayed later (measured: one further delivery
    // ~800 ms after the give-up, with the attempt count starting over). What the bound guarantees is
    // that any one run of failures ends, not that the message is never seen again.
    assertThat(deliveriesTo("poison-user")).isEqualTo(attemptLimit);

    var aside = setAsidesFor("poison-user").get(0);
    assertThat(aside.attempts()).isEqualTo(attemptLimit);
    assertThat(aside.reason()).contains("poisoned subject");

    // The set-aside is recorded as a FAILURE, with its reason — not as activity, and not silently.
    // (The feed does hold a `baseline` entry for this user: the production consumer is a SEPARATE
    // projection over the same source and is not poisoned. That independence is the point of the
    // component family, so it is asserted rather than glossed over.)
    assertThat(entriesFor("poison-user")).hasSize(1);
    assertThat(entriesFor("poison-user").get(0).change().kind()).isEqualTo("baseline");

    // And the stream is not stuck: a DIFFERENT user's change, written after the failures, gets through.
    add("bystander", "ordinary work");
    Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> !entriesFor("bystander").isEmpty());
    logger.info("US2 >>> poison deliveries={} set-asides={} bystander entries={}",
        deliveriesTo("poison-user"), setAsidesFor("poison-user").size(), entriesFor("bystander").size());
  }

  @Test
  public void aDeliveryThatFailsOnceAndThenSucceedsIsNotSetAside() {
    ProbeLog.failOnce("flaky-user");
    add("flaky-user", "eventually fine");

    // The feed records the change (the production consumer is a separate projection, unaffected by the
    // probe's failure)…
    Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> !entriesFor("flaky-user").isEmpty());
    // …and the probe saw the runtime redeliver: two deliveries, not one.
    Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> deliveriesTo("flaky-user") >= 2);

    assertThat(setAsidesFor("flaky-user")).isEmpty();
    assertThat(entriesFor("flaky-user")).hasSize(1);
    logger.info("US2 >>> flaky deliveries={} entries={} set-asides={}",
        deliveriesTo("flaky-user"), entriesFor("flaky-user").size(), setAsidesFor("flaky-user").size());
  }
}
