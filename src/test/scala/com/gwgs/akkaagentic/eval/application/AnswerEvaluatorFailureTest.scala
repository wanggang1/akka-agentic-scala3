package com.gwgs.akkaagentic.eval.application

import java.util.concurrent.{CompletableFuture, CompletionStage}

import scala.collection.mutable.ListBuffer

import akka.javasdk.Metadata
import akka.javasdk.agent.Agent
import akka.javasdk.agent.autonomous.AutonomousAgent
import akka.javasdk.client.*
import akka.pattern.RetrySettings
import com.gwgs.akkaagentic.docs.application.KnowledgeStore
import com.gwgs.akkaagentic.docs.domain.KnowledgeCorpus
import com.gwgs.akkaagentic.eval.domain.EvaluationApplicability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Path 2 of the capability-13 timeout follow-up: the assistant's call fails **outside** the agent, so
  * `DocsAgent.onFailure` never sees it and the call throws inside `AnswerEvaluator`.
  *
  * That path cannot be staged through the TestKit — every failure a scripted model can produce happens
  * *inside* the agent, which is path 1 (covered in `EvaluationErrorsIntegrationTest`). So this drives
  * `AnswerEvaluator` directly with a component client whose every agent call throws. It stubs exactly
  * one thing, the call failing; retrieval is the real in-process store.
  *
  * Before the fix this propagated out of `evaluate` and the runtime answered `POST /evaluate` with a
  * generic `500` — contradicting the documented contract that every outcome except invalid input is
  * `200`.
  */
class AnswerEvaluatorFailureTest:

  private val store = new KnowledgeStore(KnowledgeCorpus.passages)

  private val Question = "what makes agent work survive a restart without writing persistence code?"

  /** Every agent call throws, recording which component id it was asked for. `AnswerEvaluator`
    * reaches nothing but the agent client, so every other client is `???`. */
  private final class FailingClient extends ComponentClient:
    val called: ListBuffer[String] = ListBuffer.empty

    override def forTimedAction(): TimedActionClient = ???
    override def forKeyValueEntity(id: String): KeyValueEntityClient = ???
    override def forEventSourcedEntity(id: String): EventSourcedEntityClient = ???
    override def forWorkflow(id: String): WorkflowClient = ???
    override def forView(): ViewClient = ???
    override def forAutonomousAgent[T <: AutonomousAgent](cls: Class[T], id: String): AutonomousAgentClient = ???
    override def forTask(id: String): TaskClient = ???

    override def forAgent(): AgentClient = new AgentClient:
      override def inSession(sessionId: String): AgentClientInSession = new AgentClientInSession:
        override def method[T, R](f: akka.japi.function.Function[T, Agent.Effect[R]]): AgentMethodRef[R] = ???
        override def method[T, A1, R](
            f: akka.japi.function.Function2[T, A1, Agent.Effect[R]]
        ): AgentMethodRef1[A1, R] = ???
        override def tokenStream[T](
            f: akka.japi.function.Function[T, Agent.StreamEffect]
        ): ComponentStreamMethodRef[String] = ???
        override def tokenStream[T, A1](
            f: akka.japi.function.Function2[T, A1, Agent.StreamEffect]
        ): ComponentStreamMethodRef1[A1, String] = ???

        override def dynamicCall[A1, R](componentId: String): DynamicMethodRef[A1, R] =
          called += componentId
          new DynamicMethodRef[A1, R]:
            private def boom = new RuntimeException(s"simulated component-call failure reaching [$componentId]")
            override def withMetadata(metadata: Metadata): DynamicMethodRef[A1, R] = this
            override def withRetry(settings: RetrySettings): DynamicMethodRef[A1, R] = this
            override def withRetry(maxRetries: Int): DynamicMethodRef[A1, R] = this
            override def invokeAsync(arg: A1): CompletionStage[R] = CompletableFuture.failedFuture(boom)
            override def invoke(arg: A1): R = throw boom
            override def invokeAsync(): CompletionStage[R] = CompletableFuture.failedFuture(boom)
            override def invoke(): R = throw boom

  @Test
  def aCallThatFailsOutrightIsNotApplicableAndNoJudgeIsCalled(): Unit =
    val client = new FailingClient
    val evaluation = new AnswerEvaluator(client, store).evaluate(Question)

    // The evaluation completes rather than throwing — which is what keeps the endpoint at 200.
    assertThat(evaluation.answer).isEmpty()
    assertThat(evaluation.citedSources.isEmpty).isTrue()
    assertThat(evaluation.verdicts.map(v => s"${v.judge}=${v.outcome}").mkString(", "))
      .isEqualTo("hallucination-evaluator=NotApplicable, decline-judge=NotApplicable")
    evaluation.verdicts.foreach(v => assertThat(v.explanation).isEqualTo(EvaluationApplicability.FailedReason))

    // And the only call attempted was the assistant's: neither judge was reached.
    assertThat(client.called.mkString(", ")).isEqualTo("docs-agent")

  @Test
  def withJudgesSwitchedOffAFailedCallStillCompletes(): Unit =
    val evaluation = new AnswerEvaluator(new FailingClient, store).evaluate(Question, judgesEnabled = false)

    assertThat(evaluation.answer).isEmpty()
    assertThat(evaluation.citedSources.isEmpty).isTrue()
    assertThat(evaluation.verdicts.isEmpty).isTrue()
