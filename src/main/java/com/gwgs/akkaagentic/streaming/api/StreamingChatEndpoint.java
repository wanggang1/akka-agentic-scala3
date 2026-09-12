package com.gwgs.akkaagentic.streaming.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import akka.stream.javadsl.Source;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gwgs.akkaagentic.streaming.application.StreamingChatAgent;
import com.gwgs.akkaagentic.streaming.domain.StreamQuestion;
import com.typesafe.config.Config;
import scala.Option;
import scala.util.Either;
import scala.util.Left;
import scala.util.Right;

import java.time.Duration;

/**
 * Capability 14's HTTP surface: {@code POST /stream-chat/{sessionId}} delivers the answer as it is
 * generated. Capability 4's {@code POST /chat/{sessionId}} is a sibling surface and is untouched.
 *
 * <p><strong>Why this one class is Java, in an otherwise-Scala capability.</strong> Consuming an
 * agent's token stream is only expressible as {@code tokenStream(StreamingChatAgent::stream)} — a Java
 * method reference. Measured alternatives (specs/016 research Q-B): {@code dynamicCall(id).source(msg)}
 * does not compile ({@code DynamicMethodRef} has no {@code source}); {@code tokenStream("agent-id")}
 * does not compile (no {@code String} overload); and the same lambda written in Scala compiles and then
 * fails at run time with {@code IllegalArgumentException: class <the caller's own class> is not a
 * subclass of class akka.javasdk.agent.Agent}, because {@code MethodRefResolver} reads the
 * {@code SerializedLambda}'s {@code implClass}, which for a Scala lambda is the enclosing class.
 *
 * <p>So the wall claims exactly this class and travels no further — capability 11's shape. The agent,
 * the domain rule, and this endpoint's own integration test are all Scala. A test
 * ({@code JavaQuarantineTest}) pins that this stays the only Java file, so growth of the quarantine
 * becomes a recorded finding rather than silent drift (FR-013).
 *
 * <p><strong>The two guards are load-bearing, not defensive.</strong> Research Q-A(2) measured that a
 * model failure before the first token produces no tokens, no completion and no failure — nothing, ever
 * (still silent after 240 s, past the provider's own ~3-minute budget). Without
 * {@code initialTimeout} a caller would hold an open connection forever, so FR-006 would be
 * unsatisfiable. {@code idleTimeout} covers the same hazard after streaming has begun (FR-005). And
 * note what is *not* available: {@code StreamEffect} has no {@code onFailure}, so unlike every other
 * agent surface in this project there is no fallback value to fall back to — no sentinel can replace
 * text the caller has already read.
 */
@HttpEndpoint
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class StreamingChatEndpoint {

  /** Inbound body. Unknown properties tolerated; a missing {@code message} arrives as null and is
   * rejected by the domain rule rather than by a 500. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChatRequest(String message) {}

  /** How many token fragments to join into one chunk, and how long to wait before sending a short
   * group. FR-007: a client must not be forced to handle one event per token — a two-sentence answer
   * is 57 of them (measured). */
  private static final int GROUP_SIZE = 20;
  private static final Duration GROUP_WINDOW = Duration.ofMillis(100);

  private static final String FIRST_TOKEN_TIMEOUT_KEY = "streaming.first-token-timeout";
  private static final String IDLE_TIMEOUT_KEY = "streaming.idle-timeout";
  private static final Duration DEFAULT_FIRST_TOKEN_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);

  private final ComponentClient componentClient;
  private final Config config;

  public StreamingChatEndpoint(ComponentClient componentClient, Config config) {
    this.componentClient = componentClient;
    this.config = config;
  }

  @Post("/stream-chat/{sessionId}")
  public HttpResponse stream(String sessionId, ChatRequest request) {
    // Validate first: a blank question costs no model call, and the 400 is an ordinary
    // non-streamed response (FR-003). The rule lives in Scala; this is the one place in the project
    // where a Java caller reads an idiomatic Option/Either API, through Scala's static forwarders.
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

    Source<String, ?> guarded =
        tokens
            // Fail if the model never produces a first token — otherwise this hangs forever (Q-A(2)).
            .initialTimeout(duration(FIRST_TOKEN_TIMEOUT_KEY, DEFAULT_FIRST_TOKEN_TIMEOUT))
            // Fail if generation stalls after it began, so a truncated answer ends rather than dangles.
            .idleTimeout(duration(IDLE_TIMEOUT_KEY, DEFAULT_IDLE_TIMEOUT))
            .groupedWithin(GROUP_SIZE, GROUP_WINDOW)
            .map(group -> String.join("", group));

    return HttpResponses.streamText(guarded);
  }

  /** Read a guard duration from configuration, falling back to the default when absent, malformed,
   * zero or negative — a bad value must not turn every request into an instant timeout. Read per
   * request, like capability 13's switches, because an endpoint instance is per request anyway. */
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
