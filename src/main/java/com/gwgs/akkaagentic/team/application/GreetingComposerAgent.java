package com.gwgs.akkaagentic.team.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Description;
import akka.javasdk.annotations.FunctionTool;
import com.gwgs.akkaagentic.team.domain.TimeOfDay;
import com.gwgs.akkaagentic.team.domain.Tone;

/**
 * Step 2 of the greeting workflow: compose the structured greeting {@code {greeting, tone,
 * timeOfDay}} <em>given</em> the tone already detected by {@link ToneAgent}.
 *
 * <p>It both exposes a {@code @FunctionTool} (current time of day) and returns a structured result,
 * so it must use {@code responseAs} + {@code onFailure} rather than {@code responseConformsTo} —
 * Google Gemini rejects combining function calling with a JSON response mime type (research R4 /
 * feature 002). The system prompt instructs the model to emit the exact JSON shape; a non-JSON reply
 * degrades to a safe, model-free greeting instead of failing the step.
 */
@Component(id = "greeting-composer-agent")
public class GreetingComposerAgent extends Agent {

  private static final String SYSTEM_MESSAGE =
      """
      You are a friendly greeting assistant. Compose a single, warm, personalized greeting that
      addresses the user by name and acknowledges their message.

      You are given the tone/intent already detected for the message; match it rather than
      re-detecting it:
        - "question": acknowledge the question or help request warmly and signal that you are ready
          to help (do not actually answer it here).
        - "casual": reply with an equally casual, upbeat greeting.
        - otherwise: mirror that tone's formality and energy.

      Always call the time-of-day tool to find the current time of day. Pass the user's timezone
      when one is given; otherwise call it with an empty timezone so it falls back to UTC. Never
      guess the time of day yourself. You may weave a time-appropriate touch into the greeting
      (e.g. "Good morning").

      Reply with ONLY a JSON object of exactly this shape — no prose, no markdown code fences,
      nothing before or after it:
      {
        "greeting": "<the greeting text, one or two sentences>",
        "tone": "<echo exactly the detected tone you were given>",
        "timeOfDay": "<exactly the value the time-of-day tool returned: morning, afternoon, evening, or night>"
      }
      """;

  /** Compose a personalized, structured greeting for a request that already carries the tone. */
  public Effect<GreetingResult> compose(ComposeRequest request) {
    return effects()
        .systemMessage(SYSTEM_MESSAGE)
        .userMessage(userMessage(request))
        .responseAs(GreetingResult.class)
        // A non-JSON model reply (JsonParsingException) degrades to a safe greeting rather than a
        // 500: name the user, keep the detected tone, compute timeOfDay directly from the domain.
        .onFailure(
            error ->
                new GreetingResult(
                    "Hello " + request.user() + "!",
                    Tone.normalize(request.tone()),
                    TimeOfDay.now(request.timezone())))
        .thenReply();
  }

  private static String userMessage(ComposeRequest request) {
    return "The user's name is \""
        + request.user()
        + "\".\nThey sent this message: \""
        + request.text()
        + "\".\nThe detected tone/intent of their message is \""
        + Tone.normalize(request.tone())
        + "\".\n"
        + timezoneLine(request.timezone())
        + "\nGreet them, matching that tone.";
  }

  private static String timezoneLine(String timezone) {
    if (timezone != null && !timezone.isBlank()) {
      return "Their timezone is \"" + timezone.trim() + "\"; use it for the time of day.";
    }
    return "Their timezone is unknown; use UTC for the time of day.";
  }

  @FunctionTool(
      description =
          "Returns the current time of day (morning, afternoon, evening, or night) for an optional"
              + " IANA timezone id; falls back to UTC when the timezone is empty or unrecognized.")
  public String currentTimeOfDay(
      @Description("IANA timezone id, e.g. \"America/New_York\". May be empty to use UTC.")
          String timezone) {
    return TimeOfDay.now(timezone);
  }
}
