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
 *
 * <h2>Two state mechanisms (don't conflate them)</h2>
 *
 * <ul>
 *   <li><b>Workflow durable state</b> — the {@link State} record, read with {@code currentState()}
 *       and replaced with {@code updateState(...)}. This is the FSM data AND the real hand-off
 *       between steps: {@code toneStep} writes the detected tone into {@code State}, and
 *       {@code composeStep} reads it back and passes it <em>explicitly</em> in {@link ComposeRequest}.
 *   <li><b>Agent session memory</b> — the {@code SessionMemoryEntity} addressed by
 *       {@code .inSession(sessionId())}. Both agents use the SAME id (the workflow id), so with the
 *       default (limited-window) memory provider each call reads the session's prior turns and writes
 *       its own user+assistant turn back. That is how conversational context "flows" (FR-007).
 * </ul>
 *
 * The two are independent. The correctness of {@code composeStep} depends on the workflow State
 * (the explicit {@code ComposeRequest}), NOT on what the composer reads from the shared session — the
 * shared session is continuity/future-proofing here. Each agent also sets its own system message per
 * call; only the user/assistant turn history travels through the session, not system messages.
 *
 * <h2>Execution &amp; persistence model (durable FSM, not a blocking call)</h2>
 *
 * <p>A Workflow is a <b>durable finite-state machine backed by Akka persistence</b> — the same
 * persistent-component substrate as the entities. The runtime (not this code) records its progress to
 * the workflow's journal, so the FSM survives restarts. Concretely:
 *
 * <ul>
 *   <li><b>Async from the caller.</b> {@code start(...)} returns {@code Done} immediately
 *       ({@code transitionTo(toneStep).thenReply(...)}); the steps run afterward. Callers poll
 *       {@code getResult()}, which errors until {@code status == COMPLETED}.
 *   <li><b>Blocking inside a step.</b> {@code .invoke(...)} is synchronous — a step body blocks on its
 *       agent call, then returns a {@code StepEffect}.
 *   <li><b>Durable, persisted transitions.</b> This is an Akka <em>persistent</em> component: like a
 *       Key Value Entity it records state CHANGES (not developer-defined events) to the workflow's
 *       journal. Each step persists the new State + next transition before the next step runs, so a
 *       restart/rebalance/rolling-update resumes at the current non-completed step rather than from
 *       the start — a durable state machine, not an in-memory one.
 *   <li><b>Serialized per instance.</b> A workflow instance is single-writer; its steps never run
 *       concurrently. Timeouts and {@link #settings()} recovery act at these step boundaries.
 * </ul>
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
