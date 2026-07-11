package com.gwgs.akkaagentic.team.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import org.junit.jupiter.api.Test;

/** Drives {@link GreetingComposerAgent} with a mocked model (no live model, no API key). */
public class GreetingComposerAgentTest extends TestKitSupport {

  private final TestModelProvider composerModel = new TestModelProvider();

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
        .withModelProvider(GreetingComposerAgent.class, composerModel);
  }

  /** A well-formed JSON reply is parsed into the structured {@link GreetingResult}. */
  @Test
  public void structuredResponseHasAllFields() {
    var mocked = new GreetingResult("Good evening, Ada — happy to help.", "question", "evening");
    composerModel.fixedResponse(JsonSupport.encodeToString(mocked));

    var result =
        componentClient
            .forAgent()
            .inSession("test-session")
            .method(GreetingComposerAgent::compose)
            .invoke(
                new ComposeRequest(
                    "Ada", "How do I reset my password?", "question", "America/New_York"));

    assertThat(result.greeting()).isEqualTo("Good evening, Ada — happy to help.");
    assertThat(result.tone()).isEqualTo("question");
    assertThat(result.timeOfDay()).isEqualTo("evening");
  }

  /** A non-JSON reply degrades to the safe fallback (name the user, keep the tone, compute time). */
  @Test
  public void nonJsonReplyFallsBackSafely() {
    composerModel.fixedResponse("Sorry, I can't do that right now."); // not JSON → onFailure

    var result =
        componentClient
            .forAgent()
            .inSession("test-session-2")
            .method(GreetingComposerAgent::compose)
            .invoke(new ComposeRequest("Ada", "hey there", "casual", null));

    assertThat(result.greeting()).isEqualTo("Hello Ada!");
    assertThat(result.tone()).isEqualTo("casual");
    assertThat(result.timeOfDay()).isIn("morning", "afternoon", "evening", "night");
  }
}
