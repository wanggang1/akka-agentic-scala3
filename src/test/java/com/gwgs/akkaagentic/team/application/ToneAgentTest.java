package com.gwgs.akkaagentic.team.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import org.junit.jupiter.api.Test;

/** Drives {@link ToneAgent} with a mocked model (no live model, no API key). */
public class ToneAgentTest extends TestKitSupport {

  private final TestModelProvider toneModel = new TestModelProvider();

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        // Booting the service parses the default Gemini model config; supply a placeholder key so
        // startup config resolution succeeds (the model itself is mocked below).
        .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
        .withModelProvider(ToneAgent.class, toneModel);
  }

  @Test
  public void detectReturnsTheLabel() {
    toneModel.fixedResponse("question");

    var result =
        componentClient
            .forAgent()
            .inSession("test-session")
            .method(ToneAgent::detect)
            .invoke("How do I reset my password?");

    assertThat(result).isEqualTo("question");
  }
}
