package com.gwgs.akkaagentic.team.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import com.gwgs.akkaagentic.team.application.GreetingComposerAgent;
import com.gwgs.akkaagentic.team.application.GreetingResult;
import com.gwgs.akkaagentic.team.application.ToneAgent;
import java.time.Duration;
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
}
