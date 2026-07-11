package com.gwgs.akkaagentic.team.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link GreetingWorkflow} end-to-end with both agents mocked (no live model). The workflow
 * runs asynchronously, so results are polled with Awaitility (getResult errors until COMPLETED,
 * hence {@code ignoreExceptions}).
 */
public class GreetingWorkflowIntegrationTest extends TestKitSupport {

  private final TestModelProvider toneModel = new TestModelProvider();
  private final TestModelProvider composerModel = new TestModelProvider();

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
        .withModelProvider(ToneAgent.class, toneModel)
        .withModelProvider(GreetingComposerAgent.class, composerModel);
  }

  /** Tone detected in step 1 flows into step 2: the composer only replies when it sees "question". */
  @Test
  public void composesReflectingTheDetectedTone() {
    toneModel.fixedResponse("question");
    composerModel
        .whenMessage(msg -> msg.contains("question"))
        .reply(
            JsonSupport.encodeToString(
                new GreetingResult("Good evening, Ada — happy to help.", "question", "evening")));

    var id = "wf-" + UUID.randomUUID();
    componentClient
        .forWorkflow(id)
        .method(GreetingWorkflow::start)
        .invoke(new StartGreeting("Ada", "How do I reset my password?", "America/New_York"));

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .ignoreExceptions()
        .untilAsserted(
            () -> {
              var result =
                  componentClient.forWorkflow(id).method(GreetingWorkflow::getResult).invoke();
              assertThat(result.greeting()).isEqualTo("Good evening, Ada — happy to help.");
              assertThat(result.tone()).isEqualTo("question");
              assertThat(result.timeOfDay()).isEqualTo("evening");
            });
  }

  /**
   * Durability (FR-006): the tone model fails, so the tone step fails over to a neutral tone and the
   * workflow still completes. The composer only replies when it sees "neutral", proving the failover
   * value reached step 2.
   */
  @Test
  public void toneFailureFallsBackToNeutralAndStillCompletes() {
    toneModel.whenMessage(msg -> true).failWith(new RuntimeException("tone model unavailable"));
    composerModel
        .whenMessage(msg -> msg.contains("neutral"))
        .reply(JsonSupport.encodeToString(new GreetingResult("Hello Ada!", "neutral", "morning")));

    var id = "wf-failover-" + UUID.randomUUID();
    componentClient
        .forWorkflow(id)
        .method(GreetingWorkflow::start)
        .invoke(new StartGreeting("Ada", "hey there", null));

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .ignoreExceptions()
        .untilAsserted(
            () -> {
              var result =
                  componentClient.forWorkflow(id).method(GreetingWorkflow::getResult).invoke();
              assertThat(result.tone()).isEqualTo("neutral");
              assertThat(result.greeting()).isEqualTo("Hello Ada!");
            });
  }
}
