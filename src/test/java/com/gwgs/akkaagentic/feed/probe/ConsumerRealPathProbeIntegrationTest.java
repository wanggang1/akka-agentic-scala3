package com.gwgs.akkaagentic.feed.probe;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.gwgs.akkaagentic.a2a.application.TodoEntity;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.jdk.javaapi.CollectionConverters;

/**
 * Phase 0 probe for capability 16 (specs/018), the REAL path: no mocked incoming messages, so a write to
 * capability 6's {@code TodoEntity} reaches the consumer through the runtime's actual projection.
 *
 * <p>Needed because the mocked path could not answer Q-D: under the mock a failing delivery was never
 * redelivered and messages published around it were lost — not the documented at-least-once behaviour.
 *
 * <p>Java for the measured reason every entity caller in this project is: writing to {@code TodoEntity}
 * needs a {@code TodoEntity::add} method reference. It is a test, not production.
 */
public class ConsumerRealPathProbeIntegrationTest extends TestKitSupport {

  private static final Logger logger = LoggerFactory.getLogger(ConsumerRealPathProbeIntegrationTest.class);

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a");
  }

  @BeforeEach
  void reset() {
    ProbeLog.clear();
  }

  private void add(String user, String description) {
    componentClient.forKeyValueEntity(user).method(TodoEntity::add).invoke(description);
  }

  private java.util.List<ProbeLog.Observation> seen(String subject) {
    return CollectionConverters.asJava(ProbeLog.of("todo", subject));
  }

  private String describe(String subject) {
    var obs = seen(subject);
    if (obs.isEmpty()) return "none";
    long t0 = obs.get(0).atMillis();
    return obs.stream()
        .map(o -> "+" + (o.atMillis() - t0) + "ms " + o.detail().replaceAll(".*: ", "")
            + " " + CollectionConverters.asJava(o.metadataKeys()).stream().filter(k -> k.startsWith("ce-id=")).findFirst().orElse(""))
        .collect(Collectors.joining(" | "));
  }

  /** Offsets (ms) of every delivery of one exact state — i.e. the redelivery schedule of that message. */
  private String scheduleOf(String subject, String detailSuffix) {
    var obs = seen(subject).stream().filter(o -> o.detail().endsWith(detailSuffix)).toList();
    if (obs.isEmpty()) return "none";
    long t0 = obs.get(0).atMillis();
    return obs.stream().map(o -> String.valueOf(o.atMillis() - t0)).collect(Collectors.joining(", "));
  }

  @Test
  public void qD_realPath_failingDeliveryRedeliveryAndBlocking() throws Exception {
    ProbeLog.poison("real-poison");
    add("real-poison", "first");
    Awaitility.await().atMost(20, TimeUnit.SECONDS).until(() -> !seen("real-poison").isEmpty());
    add("real-poison", "second");
    add("real-bystander", "x");
    for (int i = 1; i <= 6; i++) {
      Thread.sleep(5000);
      logger.info("Q-D real >>> {}s: poison attempts={} bystanderSeen={}",
          i * 5, seen("real-poison").size(), !seen("real-bystander").isEmpty());
    }
    logger.info("Q-D real >>> redelivery schedule of the first failing state (ms): {}", scheduleOf("real-poison", "[1:first:false] next=2"));
    ProbeLog.cure("real-poison");
    Awaitility.await().atMost(60, TimeUnit.SECONDS).until(() -> !seen("real-bystander").isEmpty());
    Thread.sleep(3000);
    logger.info("Q-D real >>> after cure, poison deliveries: {}", describe("real-poison"));
    logger.info("Q-D real >>> after cure, bystander: {}", describe("real-bystander"));
  }

  @Test
  public void qD_realPath_selfBoundedGiveUp() throws Exception {
    ProbeLog.giveUpAfter(3);
    ProbeLog.poison("real-poison-2");
    add("real-poison-2", "doomed");
    // Put the stream INTO its failure loop first (the first run of this probe wrote the bystander in the
    // same instant, and it slipped through in the same batch — inconclusive). Only then write the bystander.
    Awaitility.await().atMost(20, TimeUnit.SECONDS).until(() -> seen("real-poison-2").size() >= 2);
    add("real-bystander-2", "after");
    boolean arrived;
    try {
      Awaitility.await().atMost(90, TimeUnit.SECONDS).until(() -> !seen("real-bystander-2").isEmpty());
      arrived = true;
    } catch (Throwable t) {
      arrived = false;
    }
    logger.info("Q-D(c) real >>> giveUpAfter=3: poison deliveries: {}", describe("real-poison-2"));
    var poisonLast = seen("real-poison-2").get(seen("real-poison-2").size() - 1).atMillis();
    var bystanderFirst = seen("real-bystander-2").isEmpty() ? -1 : seen("real-bystander-2").get(0).atMillis();
    logger.info("Q-D(c) real >>> bystander arrived {} ms after the poison's final (give-up) delivery", bystanderFirst - poisonLast);
    logger.info("Q-D(c) real >>> bystander arrived={} : {}", arrived, describe("real-bystander-2"));
  }
}
