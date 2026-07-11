package com.gwgs.akkaagentic.team.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import com.gwgs.akkaagentic.team.domain.Tone;
import java.time.Duration;

/**
 * Capability 2's orchestrator: runs {@link ToneAgent} then {@link GreetingComposerAgent} in a fixed
 * two-step sequence and stores the structured result.
 *
 * <p>This component is <b>Java</b> because the Workflow API wires steps only via Java method
 * references ({@code transitionTo}/{@code thenTransitionTo}/{@code stepTimeout}/{@code stepRecovery})
 * resolved through {@code SerializedLambda}, and {@code WorkflowClient} has no {@code dynamicCall} —
 * Scala cannot produce those references (see {@code specs/004} research R1). Being Java, it wires its
 * own steps natively ({@code GreetingWorkflow::toneStep}) and calls the (Scala-free) Java agents via
 * {@code .method(ToneAgent::detect)} with no friction.
 *
 * <p>Both agents share one session (the workflow id) so context flows between the steps (FR-007).
 * Durability (FR-006): a terminal tone-step failure fails over to a neutral tone and still composes;
 * the composer's own {@code onFailure} covers compose failures.
 */
@Component(id = "greeting-workflow")
public class GreetingWorkflow extends Workflow<GreetingWorkflow.State> {

  /** Durable state; a plain Java record (Java-shaped for the internal component serializer). */
  public record State(
      String user, String text, String timezone, String tone, GreetingResult result, String status) {

    public static final String STARTED = "STARTED";
    public static final String TONE_DETECTED = "TONE_DETECTED";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    static State started(StartGreeting cmd) {
      return new State(cmd.user(), cmd.text(), cmd.timezone(), null, null, STARTED);
    }

    State withTone(String detectedTone) {
      return new State(user, text, timezone, detectedTone, result, TONE_DETECTED);
    }

    State withResult(GreetingResult composed) {
      // The detected tone is authoritative; carry the composer's echoed tone as the final label.
      return new State(user, text, timezone, composed.tone(), composed, COMPLETED);
    }

    State failed() {
      return new State(user, text, timezone, tone, result, FAILED);
    }

    boolean isComplete() {
      return COMPLETED.equals(status);
    }
  }

  private final ComponentClient componentClient;

  public GreetingWorkflow(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  /** Start the greeting; returns immediately (steps run asynchronously). */
  public Effect<Done> start(StartGreeting cmd) {
    if (currentState() != null) {
      return effects().error("greeting already started");
    }
    return effects()
        .updateState(State.started(cmd))
        .transitionTo(GreetingWorkflow::toneStep)
        .thenReply(Done.getInstance());
  }

  /** Read the composed greeting; errors until the workflow has COMPLETED (endpoint maps to 404). */
  public ReadOnlyEffect<GreetingResult> getResult() {
    if (currentState() == null || !currentState().isComplete()) {
      return effects().error("greeting not ready");
    }
    return effects().reply(currentState().result());
  }

  @StepName("tone")
  private StepEffect toneStep() {
    String raw =
        componentClient
            .forAgent()
            .inSession(sessionId())
            .method(ToneAgent::detect)
            .invoke(currentState().text());

    return stepEffects()
        .updateState(currentState().withTone(Tone.normalize(raw)))
        .thenTransitionTo(GreetingWorkflow::composeStep);
  }

  @StepName("compose")
  private StepEffect composeStep() {
    var s = currentState();
    GreetingResult result =
        componentClient
            .forAgent()
            .inSession(sessionId())
            .method(GreetingComposerAgent::compose)
            .invoke(new ComposeRequest(s.user(), s.text(), s.tone(), s.timezone()));

    return stepEffects().updateState(currentState().withResult(result)).thenEnd();
  }

  /** Failover for a terminal tone-step failure: proceed with a neutral tone so we still compose. */
  @StepName("tone-fallback")
  private StepEffect toneFallbackStep() {
    return stepEffects()
        .updateState(currentState().withTone(Tone.NEUTRAL))
        .thenTransitionTo(GreetingWorkflow::composeStep);
  }

  /** Default failover: mark FAILED and stop (getResult keeps reporting not-ready). */
  @StepName("failed")
  private StepEffect failedStep() {
    return stepEffects().updateState(currentState().failed()).thenEnd();
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder()
        .stepTimeout(GreetingWorkflow::toneStep, Duration.ofSeconds(60))
        .stepTimeout(GreetingWorkflow::composeStep, Duration.ofSeconds(60))
        .stepRecovery(
            GreetingWorkflow::toneStep,
            RecoverStrategy.maxRetries(2).failoverTo(GreetingWorkflow::toneFallbackStep))
        .defaultStepRecovery(
            RecoverStrategy.maxRetries(1).failoverTo(GreetingWorkflow::failedStep))
        .build();
  }

  private String sessionId() {
    // The workflow corresponds to one greeting session; both agents share it (FR-007).
    return commandContext().workflowId();
  }
}
