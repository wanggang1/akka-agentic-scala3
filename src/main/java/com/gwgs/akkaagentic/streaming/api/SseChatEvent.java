package com.gwgs.akkaagentic.streaming.api;

/**
 * Capability 20's SSE wire envelope: the elements the {@code /sse-chat/{sessionId}} stream emits, one
 * per SSE frame. A {@link Data} element becomes an {@code event: data} frame; an {@link ErrorEvent}
 * becomes an {@code event: error} frame. The frame's {@code event:} label is derived in the endpoint's
 * {@code extractEventType} function (see {@link SseChatEndpoint}) rather than carried on the record, so
 * that the {@code data:} payload stays a clean domain object.
 *
 * <p><strong>Why this is Java, not Scala.</strong> {@code HttpResponses.serverSentEvents} renders each
 * element to the {@code data:} payload with the SDK's <em>internal</em> Jackson mapper
 * ({@code JsonSupport.getObjectMapper()}), which has no Scala module (project rule
 * {@code scala-jackson-module-followup}). So this is a wire type and is Java-shaped, exactly like every
 * other component/SSE payload in the project. A {@code data} frame therefore carries JSON — e.g.
 * {@code data: {"text":"hello "}} — not raw text (research Q-D); the SSE client reconstructs the answer
 * by concatenating the decoded {@code text} fields, not the raw payload lines.
 *
 * <p><strong>The event type is deliberately NOT a field here.</strong> A record accessor such as
 * {@code eventType()} would be picked up by Jackson and leak into the {@code data:} payload
 * ({@code {"text":"…","eventType":"data"}}). Keeping the records as pure data ({@code Data(text)} /
 * {@code ErrorEvent(reason)}) keeps each payload minimal; the endpoint maps the element's type to the
 * SSE {@code event:} label with a pattern switch.
 *
 * <p><strong>{@link ErrorEvent} is deliberately a flat record of one String.</strong> If serializing an
 * element throws, the SDK's internal {@code recoverWith} silently empties the whole stream (research
 * Q-C / Q-D.4) — re-opening the very hole this capability closes. Keeping the error element trivially
 * serializable is a correctness requirement, not a style choice.
 */
public sealed interface SseChatEvent permits SseChatEvent.Data, SseChatEvent.ErrorEvent {

  /** A text fragment of the answer. Never null — an empty group is dropped upstream of the stream. */
  record Data(String text) implements SseChatEvent {}

  /**
   * A terminal failure element. {@code reason} is a stable, client-safe description (FR-006) produced
   * by {@code SseErrorReason.reasonFor}; it never carries a stack trace or an internal class name.
   */
  record ErrorEvent(String reason) implements SseChatEvent {}
}
