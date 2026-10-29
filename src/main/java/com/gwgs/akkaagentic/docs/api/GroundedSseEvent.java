package com.gwgs.akkaagentic.docs.api;

import java.util.List;

/**
 * Capability 22's (fork B5) SSE wire envelope: the elements the {@code POST /sse-cited-ask} stream emits,
 * one per SSE frame. A {@link Data} element becomes an {@code event: data} frame carrying a fragment of
 * the <em>clean</em> answer (inline {@code [n]} markers already stripped); the single terminal
 * {@link Citations} element becomes the one {@code event: citations} frame; an {@link ErrorEvent} becomes
 * a terminal {@code event: error} frame. The {@code event:} label is derived in the endpoint from the
 * element's type (see {@code StreamingCitedDocsEndpoint}), not carried on the record, so the {@code data:}
 * payload stays a clean domain object.
 *
 * <p><strong>Why this is Java, not Scala.</strong> {@code HttpResponses.serverSentEvents} renders each
 * element to the {@code data:} payload with the SDK's <em>internal</em> Jackson mapper
 * ({@code JsonSupport.getObjectMapper()}), which has no Scala module (project rule
 * {@code scala-jackson-module-followup}). So this is a wire type and is Java-shaped, exactly like cap-20's
 * {@code SseChatEvent} and every other component/SSE payload. A {@code data} frame therefore carries JSON
 * — e.g. {@code data: {"text":"hello "}} — and the SSE client reconstructs the answer by concatenating the
 * decoded {@code text} fields.
 *
 * <p><strong>Why there is a structured {@link Citations} frame but no structured output on the stream.</strong>
 * A streamed turn has no structured-output channel (research R1), so the model's only signal for which
 * sources it used is the inline {@code [n]} markers in its prose. Those are parsed out server-side and the
 * honesty floor is applied there; this terminal frame is the single authoritative citation record
 * ({@code usedSources} ⊆ {@code retrievedSources}), emitted after all {@code data} frames. The raw markers
 * are pre-floor and never reach the client — only this frame does.
 *
 * <p><strong>{@link ErrorEvent} is deliberately a flat record of one String.</strong> If serializing an
 * element throws, the SDK's internal {@code recoverWith} silently empties the whole stream (research R5) —
 * re-opening the hole cap-20 closed. Keeping the error element trivially serializable is a correctness
 * requirement, not a style choice.
 */
public sealed interface GroundedSseEvent
    permits GroundedSseEvent.Data, GroundedSseEvent.Citations, GroundedSseEvent.ErrorEvent {

  /** A fragment of the clean answer — inline {@code [n]} markers already removed. Never null. */
  record Data(String text) implements GroundedSseEvent {}

  /**
   * The single terminal citation frame. {@code usedSources} is the usage-accurate set (the sources the
   * model reported using, intersected with what was retrieved — the honesty floor); it is a subset of
   * {@code retrievedSources} and is empty on a decline or when nothing verifiable was reported.
   * {@code retrievedSources} is the full ground-truth set that was offered as grounding, kept alongside so
   * the divergence between used and retrieved is visible from the one frame.
   */
  record Citations(List<String> usedSources, List<String> retrievedSources)
      implements GroundedSseEvent {}

  /**
   * A terminal failure element. {@code reason} is a stable, client-safe description (FR-010) produced by
   * {@code SseErrorReason.reasonFor}; it never carries a stack trace or an internal class name. Its
   * presence guarantees no {@link Citations} frame follows (research R5).
   */
  record ErrorEvent(String reason) implements GroundedSseEvent {}
}
