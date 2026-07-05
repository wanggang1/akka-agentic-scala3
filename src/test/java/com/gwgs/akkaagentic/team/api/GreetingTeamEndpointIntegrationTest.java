package com.gwgs.akkaagentic.team.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import com.gwgs.akkaagentic.team.application.GreetingComposerAgent;
import com.gwgs.akkaagentic.team.application.GreetingResult;
import com.gwgs.akkaagentic.team.application.ToneAgent;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Drives {@link GreetingTeamEndpoint} over HTTP with both agents mocked (no live model). */
public class GreetingTeamEndpointIntegrationTest extends TestKitSupport {

  private final TestModelProvider toneModel = new TestModelProvider();
  private final TestModelProvider composerModel = new TestModelProvider();

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
        .withModelProvider(ToneAgent.class, toneModel)
        .withModelProvider(GreetingComposerAgent.class, composerModel);
  }

  @BeforeEach
  public void resetModels() {
    toneModel.reset();
    composerModel.reset();
  }

  /** C1 + C3: start returns 202 + Location + id; polling yields the greeting reflecting the tone. */
  @Test
  public void startThenPollReturnsStructuredGreeting() {
    toneModel.fixedResponse("question");
    composerModel
        .whenMessage(msg -> msg.contains("question"))
        .reply(
            JsonSupport.encodeToString(
                new GreetingResult("Good evening, Ada — happy to help.", "question", "evening")));

    var accepted =
        httpClient
            .POST("/greetings")
            .withRequestBody(
                new GreetingTeamEndpoint.StartRequest(
                    "Ada", "How do I reset my password?", "America/New_York"))
            .responseBodyAs(GreetingTeamEndpoint.StartAccepted.class)
            .invoke();

    assertThat(accepted.status()).isEqualTo(StatusCodes.ACCEPTED); // C1: 202
    assertThat(accepted.httpResponse().getHeader("Location")).isPresent(); // C1: Location
    var id = accepted.body().id();
    assertThat(id).isNotBlank();

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .ignoreExceptions() // GET is 404 (responseBodyAs throws) until COMPLETED
        .untilAsserted(
            () -> {
              var reply =
                  httpClient
                      .GET("/greetings/" + id)
                      .responseBodyAs(GreetingTeamEndpoint.GreetReply.class)
                      .invoke();
              assertThat(reply.status()).isEqualTo(StatusCodes.OK); // C3: 200
              assertThat(reply.body().greeting()).isEqualTo("Good evening, Ada — happy to help.");
              assertThat(reply.body().tone()).isEqualTo("question");
              assertThat(reply.body().timeOfDay()).isEqualTo("evening");
            });
  }

  /** C6: casual message, no timezone (UTC); tone reflected in the reply. */
  @Test
  public void casualMessageWithoutTimezone() {
    toneModel.fixedResponse("casual");
    composerModel
        .whenMessage(msg -> msg.contains("casual"))
        .reply(JsonSupport.encodeToString(new GreetingResult("Hey Ada! 👋", "casual", "morning")));

    var accepted =
        httpClient
            .POST("/greetings")
            .withRequestBody(new GreetingTeamEndpoint.StartRequest("Ada", "hey there", null))
            .responseBodyAs(GreetingTeamEndpoint.StartAccepted.class)
            .invoke();

    assertThat(accepted.status()).isEqualTo(StatusCodes.ACCEPTED);
    var id = accepted.body().id();

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .ignoreExceptions()
        .untilAsserted(
            () -> {
              var reply =
                  httpClient
                      .GET("/greetings/" + id)
                      .responseBodyAs(GreetingTeamEndpoint.GreetReply.class)
                      .invoke();
              assertThat(reply.status()).isEqualTo(StatusCodes.OK);
              assertThat(reply.body().tone()).isEqualTo("casual");
            });
  }

  // --- US2: polling an in-progress / unknown greeting (contracts C2, C4) ---

  /** C2: GET right after POST — before the workflow completes — is 404, never a fabricated body. */
  @Test
  public void getBeforeCompletionReturnsNotFound() {
    // Models left unconfigured: the workflow can never reach COMPLETED, so getResult stays in error.
    var accepted =
        httpClient
            .POST("/greetings")
            .withRequestBody(new GreetingTeamEndpoint.StartRequest("Ada", "hey there", null))
            .responseBodyAs(GreetingTeamEndpoint.StartAccepted.class)
            .invoke();
    assertThat(accepted.status()).isEqualTo(StatusCodes.ACCEPTED);

    // Omit responseBodyAs so a non-2xx status doesn't throw; assert the 404 directly.
    var reply = httpClient.GET("/greetings/" + accepted.body().id()).invoke();
    assertThat(reply.status()).isEqualTo(StatusCodes.NOT_FOUND); // C2
  }

  /** C4: GET on an id that was never started is 404, with no greeting invented. */
  @Test
  public void getUnknownIdReturnsNotFound() {
    var reply = httpClient.GET("/greetings/" + UUID.randomUUID()).invoke();
    assertThat(reply.status()).isEqualTo(StatusCodes.NOT_FOUND); // C4
  }

  // --- US4: invalid input rejected before any work (contracts C7, C8, C9) ---

  /** C7: blank user and blank text are each rejected up front — no workflow started, no model call. */
  @Test
  public void blankUserOrTextRejected() {
    var blankUser =
        httpClient
            .POST("/greetings")
            .withRequestBody(new GreetingTeamEndpoint.StartRequest("", "hey there", null))
            .invoke();
    assertThat(blankUser.status()).isEqualTo(StatusCodes.BAD_REQUEST);

    var blankText =
        httpClient
            .POST("/greetings")
            .withRequestBody(new GreetingTeamEndpoint.StartRequest("Ada", "   ", null))
            .invoke();
    assertThat(blankText.status()).isEqualTo(StatusCodes.BAD_REQUEST);
  }

  /** C8: a malformed JSON body is rejected by the SDK before the handler runs. */
  @Test
  public void malformedJsonRejected() {
    var reply =
        httpClient
            .POST("/greetings")
            .withRequestBody(ContentTypes.APPLICATION_JSON, "{ \"user\": ".getBytes())
            .invoke();
    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST); // C8
  }

  /** C9: an unknown JSON property is tolerated (StartRequest is @JsonIgnoreProperties) — normal flow. */
  @Test
  public void unknownPropertyTolerated() {
    toneModel.fixedResponse("casual");
    composerModel
        .whenMessage(msg -> msg.contains("casual"))
        .reply(JsonSupport.encodeToString(new GreetingResult("Hey Ada! 👋", "casual", "morning")));

    var accepted =
        httpClient
            .POST("/greetings")
            .withRequestBody(
                ContentTypes.APPLICATION_JSON,
                "{\"user\":\"Ada\",\"text\":\"hey there\",\"surprise\":\"ignored\"}".getBytes())
            .responseBodyAs(GreetingTeamEndpoint.StartAccepted.class)
            .invoke();
    assertThat(accepted.status()).isEqualTo(StatusCodes.ACCEPTED); // C9: extra prop didn't break it
    var id = accepted.body().id();

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .ignoreExceptions()
        .untilAsserted(
            () -> {
              var reply =
                  httpClient
                      .GET("/greetings/" + id)
                      .responseBodyAs(GreetingTeamEndpoint.GreetReply.class)
                      .invoke();
              assertThat(reply.status()).isEqualTo(StatusCodes.OK);
              assertThat(reply.body().tone()).isEqualTo("casual");
            });
  }
}
