package com.gwgs.akkaagentic.team.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;

/**
 * Step 1 of the greeting workflow: classify the tone/intent of the user's message.
 *
 * <p>Single responsibility — it only detects tone; {@link GreetingComposerAgent} composes the
 * greeting from that tone. It exposes no {@code @FunctionTool} and returns a plain-text label, so
 * (unlike the composer) it is unconstrained by the Gemini tools-vs-JSON limitation and needs no
 * structured-output mode. The workflow cleans the label via {@code Tone.normalize}.
 *
 * <p>No {@code onFailure} here on purpose: a model failure propagates so the workflow's
 * {@code stepRecovery} fails over to a neutral tone (durability, FR-006) rather than the agent
 * silently absorbing it.
 */
@Component(id = "tone-agent")
public class ToneAgent extends Agent {

  private static final String SYSTEM_MESSAGE =
      """
      You classify the tone and intent of a short user message.
      Reply with exactly ONE lowercase word naming the tone/intent — nothing else: no
      punctuation, no quotes, no explanation. Examples: casual, question, formal, excited,
      grateful, frustrated. If the message asks a question or requests help, reply "question".
      If it is a casual hello or small talk, reply "casual".
      """;

  public Effect<String> detect(String text) {
    return effects().systemMessage(SYSTEM_MESSAGE).userMessage(text).thenReply();
  }
}
