package com.gwgs.akkaagentic.chat.application;

import akka.javasdk.agent.SessionHistory;
import akka.javasdk.agent.SessionMemoryEntity;
import akka.javasdk.agent.SessionMessage;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline proof of session-memory <em>retention</em> (US1) and <em>isolation</em> (US2) by reading the
 * SDK-internal {@link SessionMemoryEntity} directly after driving the real {@code ChatAgent} (mocked
 * model). This settles research R6: the mocked model only ever sees the current turn, but memory is
 * nonetheless <strong>written and readable</strong> — verified here (4 messages stored per two-turn
 * session; a never-used session is empty). The one thing still not offline-provable is <em>recall</em>
 * (the model actually using the replayed history in its answer), which the mock ignores — that is the
 * live smoke test's job.
 *
 * <p><strong>Why this test is Java, in an otherwise-Scala capability:</strong> the EventSourcedEntity
 * client is method-reference-only ({@code SessionMemoryEntity::getHistory}) with no {@code dynamicCall}
 * — the same wall as cap-2's {@code WorkflowClient} — so a Scala caller cannot query it. We match the
 * test language to what is under test: a Java SDK entity Scala can't reach.
 */
public class SessionMemoryIntegrationTest extends TestKitSupport {

  private final TestModelProvider model = new TestModelProvider();

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
        .withModelProvider(ChatAgent.class, model);
  }

  /** Drive one turn through the real agent (mocked model) in the given session. */
  private void chat(String session, String message) {
    componentClient.forAgent().inSession(session)
        .<String, String>dynamicCall("chat-agent").invoke(message);
  }

  /** Read the SDK session-memory entity for a session id (Java method-ref — Scala can't do this). */
  private SessionHistory history(String session) {
    return componentClient
        .forEventSourcedEntity(session)
        .method(SessionMemoryEntity::getHistory)
        .invoke(new SessionMemoryEntity.GetHistoryCmd());
  }

  /** US1 retention: both turns of one session are persisted (user + AI each), in order. */
  @Test
  public void retainsBothTurnsOfASession() {
    model.fixedResponse("Noted.");
    String session = UUID.randomUUID().toString();

    chat(session, "my name is Ada");
    chat(session, "what is my name?");

    var userTexts = history(session).messages().stream()
        .filter(m -> m instanceof SessionMessage.UserMessage)
        .map(m -> ((SessionMessage.UserMessage) m).text())
        .toList();
    // The runtime stored the user turns (and an AI reply after each) — memory IS written offline.
    assertThat(userTexts).containsExactly("my name is Ada", "what is my name?");
    assertThat(history(session).messages()).hasSize(4); // 2 user + 2 AI
  }

  /** US2 isolation: a fact stored on one session id does not appear under a different id. */
  @Test
  public void isolatesHistoryBySessionId() {
    model.fixedResponse("Noted.");
    String used = UUID.randomUUID().toString();
    String other = UUID.randomUUID().toString();

    chat(used, "my name is Ada");

    assertThat(history(used).messages()).isNotEmpty();
    assertThat(history(other).messages()).isEmpty(); // a different session knows nothing
  }
}
