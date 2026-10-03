package com.gwgs.akkaagentic.compaction.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.agent.SessionHistory;
import akka.javasdk.agent.SessionMemoryEntity;
import akka.javasdk.agent.SessionMessage;
import akka.javasdk.client.ComponentClient;
import java.time.Instant;

/**
 * The one Java class in capability 17, and the only place a method reference lives.
 *
 * <p><b>Why this class is Java, measured rather than assumed.</b> Three of the calls compaction needs are
 * reachable only through a Java method reference, and {@code dynamicCall} rescues none of them:
 *
 * <ul>
 *   <li>{@code SessionMemoryEntity::getHistory} and {@code ::compactHistory} — the event-sourced-entity
 *       client is method-reference only with no {@code dynamicCall} (capability 4, README §6).
 *   <li>{@code CompactionAgent::summarize} with {@code .withDetailedReply()} — the agent client's
 *       {@code dynamicCall} returns a {@code DynamicMethodRef} carrying only {@code invoke},
 *       {@code invokeAsync}, {@code withMetadata} and {@code withRetry}; {@code withDetailedReply} lives
 *       on four method-ref types, none reachable from Scala (specs/019 research S-4). That is the
 *       <em>third</em> method of the same client to land on the wrong side of the wall, after
 *       {@code invoke} (capability 1) and {@code tokenStream} (capability 14).
 * </ul>
 *
 * <p>Holding the agent call here rather than using a Scala {@code dynamicCall} is what keeps the summary's
 * token usage: without a detailed reply the compacted {@code AiMessage} would carry none and the session's
 * own {@code getTokenUsage()} would under-report for ever after. The class had to exist for the two entity
 * calls regardless, so correctness costs no extra Java. A Java method reference to a <em>Scala</em> agent
 * is already proven in production by capability 14's {@code tokenStream(StreamingChatAgent::stream)}.
 *
 * <p><b>Why it holds no logic.</b> It exposes three thin operations and decides nothing. The first draft
 * did the message mapping and the outcome decision here too, and javac refused: <em>"enum classes may not
 * be instantiated"</em> — a Java caller cannot construct a Scala 3 {@code enum} case. Rather than work
 * around that with Scala-side factories, the logic moved to {@link Compactor}, which is where it belonged:
 * this class is a port that owns method references, and nothing else. The compiler pushed the design the
 * right way.
 */
public final class SessionMemoryGateway {

  private final ComponentClient componentClient;

  public SessionMemoryGateway(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  /** Reads a session's stored history. Its {@code sequenceNumber} is the concurrency guard's input. */
  public SessionHistory history(String sessionId) {
    return componentClient
        .forEventSourcedEntity(sessionId)
        .method(SessionMemoryEntity::getHistory)
        .invoke(new SessionMemoryEntity.GetHistoryCmd());
  }

  /**
   * Summarises a rendered conversation, with the detailed reply that carries token usage.
   *
   * <p>The session id is the summariser's own, never the session being compacted: {@link CompactionAgent}
   * sets {@code MemoryProvider.none()} so nothing is written, but aiming it at the session under
   * compaction would be one edit away from feeding compaction into the history it compacts.
   */
  public Agent.AgentReply<ConversationSummary> summarize(String sessionId, String conversation) {
    return componentClient
        .forAgent()
        .inSession("compaction-" + sessionId)
        .method(CompactionAgent::summarize)
        .withDetailedReply()
        .invoke(conversation);
  }

  /**
   * Replaces the history with the summary pair.
   *
   * <p>Reports <b>nothing</b>: research R-4 measured that a stale {@code sequenceNumber} is accepted
   * silently — no exception, no result, history unchanged. Whether this did anything is established by
   * reading the history back, which {@link Compactor} does.
   */
  public void replace(
      String sessionId,
      String componentId,
      String userText,
      String aiText,
      int inputTokens,
      int outputTokens,
      long sequenceNumber) {
    Instant now = Instant.now();
    var tokenUsage = new SessionMessage.TokenUsage(inputTokens, outputTokens);
    componentClient
        .forEventSourcedEntity(sessionId)
        .method(SessionMemoryEntity::compactHistory)
        .invoke(
            new SessionMemoryEntity.CompactionCmd(
                new SessionMessage.UserMessage(now, userText, componentId),
                new SessionMessage.AiMessage(now, aiText, componentId, tokenUsage),
                sequenceNumber));
  }
}
