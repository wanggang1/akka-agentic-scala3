package com.gwgs.akkaagentic.a2a.application;

import akka.javasdk.agent.SessionHistory;
import akka.javasdk.agent.SessionMemoryEntity;
import akka.javasdk.agent.SessionMessage;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that {@link NullSafeAiContentInterceptor} actually neutralizes the qwen3 null-content
 * poisoning on the <em>write</em> path — by driving a {@code content: null} tool-call turn through the real
 * {@link PersonalAssistantAgent} (mocked model) and reading the stored message back from the SDK-internal
 * {@link SessionMemoryEntity}.
 *
 * <p>This is the one thing the Scala unit test and the offline endpoint tests can't show: that a null AI
 * {@code text} genuinely reaches the interceptor and lands in memory as {@code ""} (not null), with its
 * {@code toolCallRequests} preserved — so a later replay reads a valid history instead of the poison. The
 * live NPE itself stays live-only (the mock never replays history through the langchain4j conversion where
 * it fires, research R6); what we assert here is the <em>root cause is removed from storage</em>.
 *
 * <p><strong>Why Java, in a Scala capability:</strong> the {@code EventSourcedEntity} client is
 * method-reference-only ({@code SessionMemoryEntity::getHistory}, no {@code dynamicCall}) — the same wall as
 * cap-4's {@code SessionMemoryIntegrationTest} — so a Scala caller can't query it. Match the test language to
 * the Java SDK entity under test.
 */
public class NullContentPersistenceIntegrationTest extends TestKitSupport {

  private final TestModelProvider model = new TestModelProvider();

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
        .withModelProvider(PersonalAssistantAgent.class, model);
  }

  /** Read the SDK session-memory entity for a username (Java method-ref — Scala can't do this). */
  private SessionHistory history(String username) {
    return componentClient
        .forEventSourcedEntity(username)
        .method(SessionMemoryEntity::getHistory)
        .invoke(new SessionMemoryEntity.GetHistoryCmd());
  }

  /**
   * A null-content tool-call turn is persisted with its text normalized to "" (not null), tool call intact.
   * This is the exact shape langchain4j accepts (content:null + tool_calls) and the runtime persists as part
   * of a <em>successful</em> turn — the poison that {@code .onFailure} can't see and the interceptor must fix.
   */
  @Test
  public void nullContentToolCallTurnIsStoredAsEmptyNotNull() {
    // Model turn 1 returns content=null WITH a tool call; then a normal reply after the tool runs.
    var addReq = new TestModelProvider.ToolInvocationRequest("TodoTools_addTodo", "{\"description\":\"x\"}");
    model
        .whenMessage(m -> m.contains("addnull"))
        .reply(new TestModelProvider.AiResponse(null, List.of(addReq), Optional.empty()));
    model
        .whenToolResult(tr -> tr.name().endsWith("addTodo"))
        .thenReply(tr -> new TestModelProvider.AiResponse("added " + tr.content()));

    String username = "nullpersist-" + UUID.randomUUID();
    componentClient
        .forAgent()
        .inSession(username) // session id = username, as the endpoint does
        .<PersonalAssistantAgent.Request, String>dynamicCall("personal-assistant-agent")
        .invoke(new PersonalAssistantAgent.Request(username, "please addnull", false));

    // Find the persisted AI message that carried the (originally null) tool call.
    var aiWithToolCall = history(username).messages().stream()
        .filter(m -> m instanceof SessionMessage.AiMessage)
        .map(m -> (SessionMessage.AiMessage) m)
        .filter(ai -> !ai.toolCallRequests().isEmpty())
        .findFirst()
        .orElseThrow(() -> new AssertionError("no tool-call AI message was persisted"));

    // The interceptor rewrote null -> "" on the write path: the stored history is replay-safe...
    assertThat(aiWithToolCall.text()).isEqualTo("");
    assertThat(aiWithToolCall.text()).isNotNull();
    // ...and the tool call survived, so an in-flight tool invocation still ran (item was added).
    assertThat(aiWithToolCall.toolCallRequests()).hasSize(1);
    assertThat(aiWithToolCall.toolCallRequests().get(0).name()).isEqualTo("TodoTools_addTodo");
  }
}
