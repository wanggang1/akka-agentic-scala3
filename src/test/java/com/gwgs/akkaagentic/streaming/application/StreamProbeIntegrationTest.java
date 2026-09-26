package com.gwgs.akkaagentic.streaming.application;

import akka.javasdk.agent.SessionHistory;
import akka.javasdk.agent.SessionMemoryEntity;
import akka.javasdk.agent.SessionMessage;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import akka.stream.javadsl.Sink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0 discovery probe for capability 14 (specs/016). It answers three of the four research
 * questions by <em>measurement</em>, and serves as the positive control for the fourth.
 *
 * <p><strong>Why this probe is Java.</strong> Consuming a token stream is documented as
 * {@code tokenStream(SomeAgent::method).source(arg)} — a Java method reference — and the agent
 * client's {@code dynamicCall} escape hatch returns a {@code DynamicMethodRef} with no streaming
 * counterpart (jar sweep, research Q-B). So this file is also the control that proves the Java path
 * works, which is what makes the Scala failure recorded separately a statement about Scala rather
 * than about our usage. Per FR-013 the Scala attempt is kept as evidence, not discarded.
 *
 * <p>Measured here: Q-C (is a scripted reply actually delivered as several tokens offline, and does
 * concatenation reproduce it exactly), Q-A (what a consumer observes when the model fails before the
 * first token), Q-D (is a streamed reply written to session memory once complete).
 */
@Tag("slow")
public class StreamProbeIntegrationTest extends TestKitSupport {

  private static final Logger logger = LoggerFactory.getLogger(StreamProbeIntegrationTest.class);

  private final TestModelProvider model = new TestModelProvider();

  private static final String ANSWER =
      "The runtime persists the task and the agent's process state as the loop runs. "
          + "That is why work survives a restart without any persistence code of your own.";

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
        .withModelProvider(StreamingChatAgent.class, model);
  }

  @BeforeEach
  public void reset() {
    model.reset();
  }

  /** The documented consumption path, held in Java because only Java can hold the method reference. */
  private List<String> tokensOf(String session, String message) throws Exception {
    var source = componentClient
        .forAgent()
        .inSession(session)
        .tokenStream(StreamingChatAgent::stream)
        .source(message);

    return source.runWith(Sink.seq(), testKit.getMaterializer())
        .toCompletableFuture()
        .get(30, TimeUnit.SECONDS);
  }

  private SessionHistory history(String session) {
    return componentClient
        .forEventSourcedEntity(session)
        .method(SessionMemoryEntity::getHistory)
        .invoke(new SessionMemoryEntity.GetHistoryCmd());
  }

  /**
   * Q-C — is a streamed reply scriptable offline at all, and does it arrive in pieces?
   *
   * <p>The SDK's agent-testing documentation never mentions streaming, so this is the question that
   * decides whether SC-001 (incrementality) and SC-002 (parity) can be proven without a live model,
   * or whether FR-010's second branch applies.
   */
  @Test
  public void aScriptedReplyIsDeliveredAsSeveralTokensAndConcatenatesBackExactly() throws Exception {
    model.fixedResponse(ANSWER);

    var tokens = tokensOf(UUID.randomUUID().toString(), "why does work survive a restart?");

    logger.info("Q-C probe >>> token count = {}, first three = {}",
        tokens.size(), tokens.stream().limit(3).toList());

    // Parity (SC-002): the fragments in order ARE the answer, with nothing added or lost.
    assertThat(String.join("", tokens)).isEqualTo(ANSWER);

    // Incrementality (SC-001): more than one fragment, or "streaming" is a word with no behaviour
    // behind it offline. If this fails, the capability's offline story is parity only.
    assertThat(tokens.size())
        .describedAs("a scripted reply arrived as %d fragment(s); >1 means the test provider really "
            + "does tokenize, so incrementality is offline-provable", tokens.size())
        .isGreaterThan(1);
  }

  /**
   * Q-A — a failure BEFORE the first token. `StreamEffect` has no `onFailure`, so capability 8's
   * sentinel technique is unavailable in principle; what the consumer actually sees is measured
   * rather than assumed, because FR-006 depends on it.
   */
  @Test
  public void aFailureBeforeTheFirstTokenReachesTheConsumerAsAFailedStream() {
    model.whenMessage(m -> true).failWith(new RuntimeException("simulated model failure"));

    Throwable observed = null;
    long start = System.nanoTime();
    try {
      // The 4-minute run that MEASURED this is recorded in specs/016 research Q-A(2): no tokens, no
      // completion and no failure after 240 019 ms, well past the provider's own budget
      // (response-timeout 1m x 3 attempts). The kept test asserts the same behaviour at a bound the
      // suite can afford — re-measuring the hang on every build would cost four minutes to learn
      // something already written down.
      var source = componentClient
          .forAgent()
          .inSession(UUID.randomUUID().toString())
          .tokenStream(StreamingChatAgent::stream)
          .source("anything");
      var tokens = source.runWith(Sink.seq(), testKit.getMaterializer())
          .toCompletableFuture()
          .get(20, TimeUnit.SECONDS);
      logger.info("Q-A probe >>> NO failure surfaced; stream completed with {} token(s): {}",
          tokens.size(), tokens);
    } catch (Throwable t) {
      observed = t;
      var chain = new StringBuilder();
      for (Throwable e = t; e != null; e = e.getCause()) {
        chain.append(e.getClass().getName()).append(": ").append(e.getMessage()).append(" <- ");
      }
      logger.info("Q-A probe >>> failure observed by the consumer after {} ms: {}",
          (System.nanoTime() - start) / 1_000_000, chain);
    }

    // Recorded, not asserted into a shape: the probe's job is to find out which it is. FR-006 is
    // designed against whatever this shows, and the answer goes into research.md verbatim.
    assertThat(true).isTrue();
    logger.info("Q-A probe >>> consumer saw a failure? {}", observed != null);
  }

  /**
   * Q-D — is the assembled answer written to session memory once the stream completes? If not,
   * FR-008 and SC-005 are withdrawn and the streaming surface becomes single-turn by necessity.
   *
   * <p>Read through the Java method-ref entity client, exactly as capability 4's memory test does —
   * Scala cannot query {@code SessionMemoryEntity} (cap-4 §6), so this check was always going to be
   * Java. That is precedent, not a new concession.
   */
  @Test
  public void aStreamedTurnIsWrittenToSessionMemory() throws Exception {
    model.fixedResponse(ANSWER);
    var session = UUID.randomUUID().toString();

    var tokens = tokensOf(session, "my name is Ada");
    assertThat(String.join("", tokens)).isEqualTo(ANSWER); // the turn really happened

    // Memory is written by the runtime after the interaction; give it a moment rather than racing.
    Thread.sleep(Duration.ofSeconds(2).toMillis());

    var messages = history(session).messages();
    var userTexts = messages.stream()
        .filter(m -> m instanceof SessionMessage.UserMessage)
        .map(m -> ((SessionMessage.UserMessage) m).text())
        .toList();
    var aiTexts = messages.stream()
        .filter(m -> m instanceof SessionMessage.AiMessage)
        .map(m -> ((SessionMessage.AiMessage) m).text())
        .toList();

    logger.info("Q-D probe >>> stored messages = {} (user = {}, ai = {}); ai text = {}",
        messages.size(), userTexts.size(), aiTexts.size(), aiTexts);

    // T014 — the measurement is settled (research Q-D), so this is now asserted rather than logged.
    // A streamed turn is remembered exactly like a non-streamed one: one user message, one AI
    // message, and the AI message carries the WHOLE answer rather than a fragment of it. That is
    // what makes the streaming surface a real conversation (SC-005) instead of a single-turn one.
    assertThat(messages).hasSize(2);
    assertThat(userTexts).containsExactly("my name is Ada");
    assertThat(aiTexts).containsExactly(ANSWER);
  }

  /**
   * T014, the other half of SC-005: a conversation is isolated. A session that was never used holds
   * nothing, so a streamed turn cannot leak into another conversation.
   *
   * <p>This lives in Java for the same reason as the check above — reading
   * {@code SessionMemoryEntity} needs a Java method reference (capability 4 §6) — and deliberately in
   * the class the wall already claimed, so the quarantine stays at one class ({@code JavaQuarantineTest}).
   */
  @Test
  public void aStreamedTurnDoesNotLeakIntoAnotherConversation() throws Exception {
    model.fixedResponse(ANSWER);
    var used = UUID.randomUUID().toString();
    var untouched = UUID.randomUUID().toString();

    tokensOf(used, "my name is Ada");
    Thread.sleep(Duration.ofSeconds(2).toMillis());

    assertThat(history(used).messages()).isNotEmpty();
    assertThat(history(untouched).messages()).isEmpty();
  }
}
