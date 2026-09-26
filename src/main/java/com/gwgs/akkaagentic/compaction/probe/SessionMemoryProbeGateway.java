package com.gwgs.akkaagentic.compaction.probe;

import akka.javasdk.agent.SessionHistory;
import akka.javasdk.agent.SessionMemoryEntity;
import akka.javasdk.agent.SessionMessage;
import akka.javasdk.client.ComponentClient;

/**
 * Phase 0 probe for feature 019 (R-3, R-4). NOT production.
 *
 * <p>Q-B asks how small the Java quarantine can be. Reading and replacing session history needs the
 * event-sourced-entity client, which capability 4 measured as method-reference only with no
 * {@code dynamicCall}, so <em>some</em> Java is forced. This class tests whether <b>one</b> class can hold
 * every method reference the capability needs — both entity calls here, and (research S-4) the agent call
 * too, since {@code withDetailedReply()} is also method-ref only and would otherwise cost the summary's
 * token usage.
 *
 * <p>It is deliberately a plain class, not a component: the same shape as capability 6's {@code TodoTools}
 * and capability 11's querying endpoint — a port that owns the method references and nothing else.
 */
public final class SessionMemoryProbeGateway {

  private final ComponentClient componentClient;

  public SessionMemoryProbeGateway(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  /** R-3: the read. Also yields the sequence number the concurrency guard needs. */
  public SessionHistory history(String sessionId) {
    return componentClient
        .forEventSourcedEntity(sessionId)
        .method(SessionMemoryEntity::getHistory)
        .invoke(new SessionMemoryEntity.GetHistoryCmd());
  }

  /**
   * R-3/R-4: the write. {@code sequenceNumber} is the guard — passing a stale one is how R-4 finds out
   * whether the entity rejects it, and what the caller sees when it does.
   */
  public void compact(String sessionId, String componentId, String userText, String aiText, long sequenceNumber) {
    var now = java.time.Instant.now();
    componentClient
        .forEventSourcedEntity(sessionId)
        .method(SessionMemoryEntity::compactHistory)
        .invoke(
            new SessionMemoryEntity.CompactionCmd(
                new SessionMessage.UserMessage(now, userText, componentId),
                new SessionMessage.AiMessage(now, aiText, componentId),
                sequenceNumber));
  }
}
