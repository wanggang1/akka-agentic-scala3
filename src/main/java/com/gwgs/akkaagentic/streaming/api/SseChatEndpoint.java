package com.gwgs.akkaagentic.streaming.api;

import akka.http.javadsl.model.HttpResponse;
import akka.japi.pf.PFBuilder;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import akka.stream.javadsl.Source;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gwgs.akkaagentic.streaming.application.StreamingChatAgent;
import com.gwgs.akkaagentic.streaming.domain.SseErrorReason;
import com.gwgs.akkaagentic.streaming.domain.StreamQuestion;
import com.typesafe.config.Config;
import scala.Option;
import scala.util.Either;
import scala.util.Left;
import scala.util.Right;

import java.time.Duration;
import java.util.function.Function;

/**
 * Capability 20's HTTP surface: {@code POST /sse-chat/{sessionId}} delivers the same agent's answer as
 * capability 14, but framed as <strong>Server-Sent Events</strong> ({@code text/event-stream}) with
 * explicit {@code event: data} / {@code event: error} frames. Capability 14's raw-chunked
 * {@code POST /stream-chat/{sessionId}} is a sibling surface and is <strong>untouched</strong> — it
 * remains the baseline this surface is measured against (FR-010).
 *
 * <p><strong>Why SSE, when cap-14 already streams.</strong> Raw chunked {@code text/plain} has no slot
 * for an error once the response has started: the {@code 200} and headers are committed before the first
 * token exists, so a failure before the first fragment is a {@code 200} with an empty body, byte-identical
 * to a successful empty answer. SSE's {@code event:} label gives a frame kind, so a failure becomes a
 * self-describing {@code event: error} frame (added in US2).
 *
 * <p><strong>Why this one class is Java, in an otherwise-Scala capability.</strong> Consuming an agent's
 * token stream is only expressible as {@code tokenStream(StreamingChatAgent::stream)} — a Java method
 * reference (README §16; specs/016 research Q-B). The agent, the domain rule
 * ({@link StreamQuestion}, {@code SseErrorReason}) and this endpoint's own integration test are all
 * Scala. Because the javac annotation processor is disabled for this mixed build ({@code -proc:none}),
 * this endpoint is registered by hand in the component descriptor, like cap-14's (specs/022 T002/T008b).
 *
 * <p><strong>The event envelope is JSON on the wire.</strong> {@code HttpResponses.serverSentEvents}
 * renders each element with the SDK's internal Jackson mapper, so a {@code data} frame carries
 * {@code {"text":"…"}} (research Q-D); clients reconstruct the answer from the decoded {@code text}
 * fields. The SSE {@code event:} label is derived here, not carried on the record (see
 * {@link SseChatEvent}).
 */
@HttpEndpoint
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class SseChatEndpoint {

  /** Inbound body. Unknown properties tolerated; a missing {@code message} arrives as null and is
   * rejected by the domain rule rather than by a 500 (feature 003 Option boundary). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChatRequest(String message) {}

  /** How many token fragments to join into one frame, and how long to wait before sending a short
   * group — reused from cap-14: a client must not be forced to handle one event per token (a
   * two-sentence answer is 57 of them, measured). */
  private static final int GROUP_SIZE = 20;
  private static final Duration GROUP_WINDOW = Duration.ofMillis(100);

  private static final String FIRST_TOKEN_TIMEOUT_KEY = "streaming.first-token-timeout";
  private static final String IDLE_TIMEOUT_KEY = "streaming.idle-timeout";
  private static final Duration DEFAULT_FIRST_TOKEN_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);

  private final ComponentClient componentClient;
  private final Config config;

  public SseChatEndpoint(ComponentClient componentClient, Config config) {
    this.componentClient = componentClient;
    this.config = config;
  }

  @Post("/sse-chat/{sessionId}")
  public HttpResponse stream(String sessionId, ChatRequest request) {
    // Validate first: a blank question costs no model call, and the 400 is an ordinary non-streamed
    // response (FR-003), identical to cap-14. The rule lives in Scala and is read here through Scala's
    // static forwarders.
    Either<String, StreamQuestion> validated =
        StreamQuestion.validate(Option.apply(request == null ? null : request.message()));

    if (validated.isLeft()) {
      return HttpResponses.badRequest(((Left<String, StreamQuestion>) validated).value());
    }
    var question = ((Right<String, StreamQuestion>) validated).value().question();

    Source<String, ?> tokens =
        componentClient
            .forAgent()
            .inSession(sessionId)
            .tokenStream(StreamingChatAgent::stream) // the method reference that forces this class
            .source(question);

    Source<SseChatEvent, ?> events =
        tokens
            // Fail if the model never produces a first token, or stalls after it began — otherwise the
            // stream hangs. Both guards raise a TimeoutException INTO the stream (FR-009).
            .initialTimeout(duration(FIRST_TOKEN_TIMEOUT_KEY, DEFAULT_FIRST_TOKEN_TIMEOUT))
            .idleTimeout(duration(IDLE_TIMEOUT_KEY, DEFAULT_IDLE_TIMEOUT))
            .groupedWithin(GROUP_SIZE, GROUP_WINDOW)
            .map(group -> (SseChatEvent) new SseChatEvent.Data(String.join("", group)))
            // THE load-bearing move (research Q-C): convert a stream failure — a model error, or one of
            // the timeout guards above — into a FINAL event: error element, so it flows through as a
            // normal frame. Without this, a Throwable reaching serverSentEvents is logged and the stream
            // is silently emptied (the SDK's own recoverWith: "no natural way to convey stream errors to
            // client with SSE"), which would reproduce cap-14's silent-empty-200 defect over SSE.
            .recover(
                new PFBuilder<Throwable, SseChatEvent>()
                    .matchAny(t -> new SseChatEvent.ErrorEvent(SseErrorReason.reasonFor(t)))
                    .build());

    return HttpResponses.serverSentEvents(events, SSE_EVENT_ID, SSE_EVENT_TYPE);
  }

  /** The SSE {@code event:} label per element: {@code "data"} or {@code "error"} (FR-002/FR-004). */
  private static final Function<SseChatEvent, String> SSE_EVENT_TYPE =
      event ->
          switch (event) {
            case SseChatEvent.Data ignored -> "data";
            case SseChatEvent.ErrorEvent ignored -> "error";
          };

  /** The 3-arg {@code serverSentEvents} overload (the only one that sets event type) also requires an
   * id function. This surface is a one-shot answer, not a resumable stream, so there is no meaningful
   * id (research Q-E); a constant empty id satisfies the signature without advertising reconnection. */
  private static final Function<SseChatEvent, String> SSE_EVENT_ID = event -> "";

  /** Read a guard duration from configuration, falling back to the default when absent, malformed,
   * zero or negative — a bad value must not turn every request into an instant timeout. */
  private Duration duration(String key, Duration fallback) {
    try {
      var configured = config.getDuration(key);
      if (configured.isZero() || configured.isNegative()) {
        return fallback;
      }
      return configured;
    } catch (RuntimeException notConfigured) {
      return fallback;
    }
  }
}
