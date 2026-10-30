package com.gwgs.akkaagentic.docs.api;

import akka.http.javadsl.model.HttpResponse;
import akka.japi.Pair;
import akka.japi.pf.PFBuilder;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import akka.stream.javadsl.Source;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gwgs.akkaagentic.docs.application.DocsAgent;
import com.gwgs.akkaagentic.docs.application.GroundedCitedPrep;
import com.gwgs.akkaagentic.docs.application.KnowledgeStore;
import com.gwgs.akkaagentic.docs.application.StreamingCitedDocsAgent;
import com.gwgs.akkaagentic.docs.domain.AskQuestion;
import com.gwgs.akkaagentic.docs.domain.CitationMarkers;
import com.gwgs.akkaagentic.streaming.domain.SseErrorReason;
import com.typesafe.config.Config;
import scala.Option;
import scala.util.Either;
import scala.util.Left;
import scala.util.Right;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Capability 22's (fork B5) HTTP surface: {@code POST /sse-cited-ask} streams a grounded answer as
 * Server-Sent Events and then cites it honestly. It combines cap-20's SSE framing with cap-21's honesty
 * floor, over a streamed turn — which forces a new mechanism, because a streamed turn has no structured
 * output channel (research R1).
 *
 * <p><strong>The shape of a response.</strong> Zero or more {@code event: data} frames carry fragments of
 * the <em>clean</em> answer (the model's inline {@code [n]} markers already stripped), followed by exactly
 * one terminal {@code event: citations} frame whose {@code usedSources} is a subset of
 * {@code retrievedSources} (the honesty floor). A failure anywhere before the answer completes ends the
 * stream with a single {@code event: error} frame and <strong>no</strong> {@code citations} frame.
 *
 * <p><strong>Why this one class is Java.</strong> Consuming an agent's token stream is only expressible as
 * {@code tokenStream(StreamingCitedDocsAgent::stream)} — a Java method reference (README §16; specs/016
 * research Q-B), exactly as cap-14/20. Everything it reaches — the agent, the stripper
 * ({@link CitationMarkers}), the retrieval + floor façade ({@link GroundedCitedPrep}), validation
 * ({@link AskQuestion}) and the error-reason map ({@code SseErrorReason}) — is Scala, reached through
 * static forwarders and DI. The wall claims only this class and the wire type {@link GroundedSseEvent}.
 * Registered by hand in the component descriptor ({@code -proc:none}, no auto-discovery).
 *
 * <p><strong>The two load-bearing stream moves</strong> (research R3/R5): (1) {@link CitationMarkers}
 * threaded through {@code statefulMap} strips the inline markers as they stream, carrying a partial marker
 * across a chunk boundary and accumulating the reported numbers into an {@link AtomicReference}; (2) a
 * lazy {@code citations} source {@code concat}-ed after the data source reads those accumulated numbers at
 * exactly the right moment and makes "failure ⇒ no citations frame" automatic — the second source is never
 * subscribed if the first fails — while {@code .recover} turns the failure into a terminal {@code error}
 * element before the SDK's SSE helper can silently empty the stream (cap-20's finding).
 */
@HttpEndpoint
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class StreamingCitedDocsEndpoint {

  /** Inbound body. Unknown properties tolerated; a missing {@code question} arrives as null and is
   * rejected by the domain rule rather than by a 500 (feature 003 Option boundary). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChatRequest(String question) {}

  /** How many passages to retrieve and offer as grounding context — same K as cap-8/21. */
  private static final int TOP_K = 3;

  /** How many token fragments to join into one frame, and how long to wait before sending a short group —
   * reused from cap-14/20: a client must not be forced to handle one event per token. */
  private static final int GROUP_SIZE = 20;
  private static final Duration GROUP_WINDOW = Duration.ofMillis(100);

  /** The decline is detected on the leading clean prose, so only this many characters of it are ever
   * retained — enough to hold the decline sentinel plus any leading whitespace, never the whole answer
   * (research R3: the prose is not buffered). */
  private static final int DECLINE_PREFIX_CAP = 48;

  // Reuse cap-20's streaming guard configuration keys and defaults verbatim.
  private static final String FIRST_TOKEN_TIMEOUT_KEY = "streaming.first-token-timeout";
  private static final String IDLE_TIMEOUT_KEY = "streaming.idle-timeout";
  private static final Duration DEFAULT_FIRST_TOKEN_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);

  private final ComponentClient componentClient;
  private final Config config;
  private final KnowledgeStore knowledgeStore;

  public StreamingCitedDocsEndpoint(
      ComponentClient componentClient, Config config, KnowledgeStore knowledgeStore) {
    this.componentClient = componentClient;
    this.config = config;
    this.knowledgeStore = knowledgeStore;
  }

  @Post("/sse-cited-ask")
  public HttpResponse ask(ChatRequest request) {
    // Validate first: a blank/absent question costs no retrieval and no model call, and the 400 is an
    // ordinary non-streamed response (FR-012). The rule lives in Scala, read here via a static forwarder.
    Either<String, AskQuestion> validated =
        AskQuestion.validate(Option.apply(request == null ? null : request.question()));
    if (validated.isLeft()) {
      return HttpResponses.badRequest(((Left<String, AskQuestion>) validated).value());
    }
    var question = ((Right<String, AskQuestion>) validated).value().question();

    // Retrieve up-front and build the agent request + the presented-source set + the retrieved labels.
    var prepared = GroundedCitedPrep.prepare(knowledgeStore, question, TOP_K);

    var tokens =
        componentClient
            .forAgent()
            .inSession(UUID.randomUUID().toString()) // each question independent (agent uses no memory)
            .tokenStream(StreamingCitedDocsAgent::stream) // the method reference that forces this class
            .source(prepared.request());

    // The model's reported citation numbers, accumulated across the stream as the stripper extracts them.
    AtomicReference<List<Integer>> reported = new AtomicReference<>(List.of());
    // The leading clean prose, bounded to DECLINE_PREFIX_CAP — just enough to decide whether the answer is
    // the decline sentinel, without buffering the whole answer (US2 / FR-007).
    AtomicReference<String> cleanPrefix = new AtomicReference<>("");

    Source<GroundedSseEvent, ?> data =
        tokens
            // Fail if the model never produces a first token, or stalls after it began — otherwise the
            // stream hangs. Both raise a TimeoutException INTO the stream (handled by .recover below).
            .initialTimeout(duration(FIRST_TOKEN_TIMEOUT_KEY, DEFAULT_FIRST_TOKEN_TIMEOUT))
            .idleTimeout(duration(IDLE_TIMEOUT_KEY, DEFAULT_IDLE_TIMEOUT))
            .groupedWithin(GROUP_SIZE, GROUP_WINDOW)
            .map(group -> String.join("", group))
            // Strip inline [n] markers incrementally; carry a partial marker across the group boundary;
            // accumulate the extracted numbers; flush any held clean tail at completion.
            .statefulMap(
                CitationMarkers::empty,
                (carry, chunk) -> {
                  var r = CitationMarkers.step(carry, chunk);
                  reported.updateAndGet(acc -> concat(acc, r.numbers()));
                  accumulatePrefix(cleanPrefix, r.emit());
                  return Pair.create(r.carry(), r.emit());
                },
                carry -> {
                  var f = CitationMarkers.finish(carry);
                  reported.updateAndGet(acc -> concat(acc, f.numbers()));
                  accumulatePrefix(cleanPrefix, f.emit());
                  return Optional.of(f.emit());
                })
            .filter(text -> !text.isEmpty())
            .map(text -> (GroundedSseEvent) new GroundedSseEvent.Data(text));

    // Read the accumulated numbers only AFTER the data stream completes. Two things make the moment
    // correct: `lazySource` defers its factory to materialisation, and `concatLazy` (NOT plain `concat`,
    // which eagerly materialises its second source at stream start) materialises this source only once
    // the data source has finished — and never if it failed, so a failed stream emits no citations frame.
    Source<GroundedSseEvent, ?> citations =
        Source.lazySource(
            () ->
                Source.single(
                    (GroundedSseEvent)
                        GroundedCitedPrep.citationsEvent(
                            prepared.sources(),
                            reported.get(),
                            isDecline(cleanPrefix.get()), // a decline cites nothing (FR-007)
                            prepared.retrievedLabels())));

    Source<GroundedSseEvent, ?> events =
        data.concatLazy(citations)
            // THE load-bearing move (cap-20): convert a stream failure into a FINAL error element, so it
            // flows as a normal frame instead of being logged and silently dropped by the SSE helper.
            .recover(
                new PFBuilder<Throwable, GroundedSseEvent>()
                    .matchAny(t -> new GroundedSseEvent.ErrorEvent(SseErrorReason.reasonFor(t)))
                    .build());

    return HttpResponses.serverSentEvents(events, SSE_EVENT_ID, SSE_EVENT_TYPE);
  }

  /** The SSE {@code event:} label per element: {@code data} | {@code citations} | {@code error}. */
  private static final Function<GroundedSseEvent, String> SSE_EVENT_TYPE =
      event ->
          switch (event) {
            case GroundedSseEvent.Data ignored -> "data";
            case GroundedSseEvent.Citations ignored -> "citations";
            case GroundedSseEvent.ErrorEvent ignored -> "error";
          };

  /** A one-shot answer, not a resumable stream, so there is no meaningful id (cap-20); a constant empty
   * id satisfies the 3-arg {@code serverSentEvents} signature without advertising reconnection. */
  private static final Function<GroundedSseEvent, String> SSE_EVENT_ID = event -> "";

  /** Grow the retained clean prefix with newly-emitted clean text, but never past {@link
   * #DECLINE_PREFIX_CAP} — the decline decision needs only the start of the prose, not the whole answer. */
  private static void accumulatePrefix(AtomicReference<String> prefix, String more) {
    if (more.isEmpty()) {
      return;
    }
    prefix.updateAndGet(p -> p.length() >= DECLINE_PREFIX_CAP ? p : p + more);
  }

  /** True when the (stripped) answer is the decline sentinel — normalized trimmed, case-insensitive,
   * mirroring cap-8/21's {@code DocsEndpoint.isDecline}. A decline must cite nothing (FR-007). */
  private static boolean isDecline(String answer) {
    return answer.trim().toLowerCase().startsWith(DocsAgent.DontKnow().toLowerCase());
  }

  /** Append two number lists into a new immutable list (the AtomicReference holds an immutable value). */
  private static List<Integer> concat(List<Integer> acc, List<Integer> more) {
    var out = new ArrayList<Integer>(acc);
    out.addAll(more);
    return List.copyOf(out);
  }

  /** Read a guard duration from configuration, falling back to the default when absent, malformed, zero
   * or negative — a bad value must not turn every request into an instant timeout. */
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
