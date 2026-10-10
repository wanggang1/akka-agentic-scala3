package com.gwgs.akkaagentic.docs.api

import scala.jdk.CollectionConverters.*

import akka.http.javadsl.model.StatusCodes
import akka.javasdk.JsonSupport
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.docs.application.{CitingDocsAgent, DocsAgent}
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

/** Drives [[CitedDocsEndpoint]] over HTTP with [[CitingDocsAgent]]'s model mocked (capability 21, fork
  * B4). The structured `{answer, usedSources}` reply is mocked here; the **retrieval** it is cited
  * against is the real in-process embeddings (deterministic), so the usage-accurate citation is asserted
  * against the passages retrieval actually returned. `citing-docs-agent` is deliberately ungoverned
  * (research R5), so no guardrail `422` path is exercised.
  */
class CitedDocsEndpointIntegrationTest extends TestKitSupport:

  private val citingModel = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[CitingDocsAgent], citingModel)

  @BeforeEach
  def reset(): Unit = citingModel.reset()

  /** Mock a structured reply as the model would emit it (JSON for `responseConformsTo`). */
  private def mockReply(answer: String, usedSources: String*): Unit =
    citingModel.fixedResponse(
      JsonSupport.encodeToString(
        CitingDocsAgent.CitedAnswer(answer, java.util.List.copyOf(java.util.Arrays.asList(usedSources*)))
      )
    )

  private def ask(question: String) =
    httpClient
      .POST("/cited-ask")
      .withRequestBody(CitedDocsEndpoint.CitedAskRequest(Some(question)))
      .responseBodyAs(classOf[CitedDocsEndpoint.CitedAskReply])
      .invoke()

  /** US1 / SC-001 / SC-002: an in-corpus question whose answer uses ONE of the three retrieved passages
    * cites only that one — a strict subset of what was retrieved — and every cited label was retrieved.
    * This is the whole point of B4: fewer citations than cap-8's `/ask` would give for the same question.
    */
  @Test
  def inCorpusAnswerCitesOnlyTheUsedSource(): Unit =
    val grounded = "The runtime persists the task and the agent's state as the loop runs, so the work survives a restart."
    mockReply(grounded, "durability-tasks") // model reports using just one of the retrieved passages

    val reply = ask("what makes agent work survive a restart without writing persistence code?")

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    assertThat(reply.body().answer).isEqualTo(grounded)
    // usage-accurate: exactly the one source the model reported using
    assertThat(reply.body().citedSources.asJava).containsExactly("durability-tasks")
    // ground truth kept alongside (FR-006), and strictly larger — proving reduced over-citation (SC-001)
    assertThat(reply.body().retrievedSources.asJava).contains("durability-tasks")
    assertThat(reply.body().citedSources.size).isLessThan(reply.body().retrievedSources.size)
    // invariant: everything cited was retrieved (SC-002)
    assertThat(reply.body().retrievedSources.asJava).containsAll(reply.body().citedSources.asJava)

  /** US2 / SC-003: an out-of-corpus question declines and cites nothing — no fabricated source, even
    * though passages were retrieved and offered. */
  @Test
  def outOfCorpusQuestionDeclinesWithNoCitation(): Unit =
    mockReply(DocsAgent.DontKnow) // decline, empty usedSources

    val reply = ask("what is the capital of France?")

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    assertThat(reply.body().answer).isEqualTo(DocsAgent.DontKnow)
    assertThat(reply.body().citedSources.asJava).isEmpty()

  /** US2 scenario 2 (FR-005): a decline OVERRIDES any reported usage. Even if the model declines yet
    * names sources, the surface cites nothing — a decline has no citations by definition. */
  @Test
  def declineOverridesReportedSources(): Unit =
    mockReply(DocsAgent.DontKnow, "durability-tasks", "cap-3-help-desk") // contradictory, but a decline

    val reply = ask("what is the capital of France?")

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    assertThat(reply.body().answer).isEqualTo(DocsAgent.DontKnow)
    assertThat(reply.body().citedSources.asJava).isEmpty()

  /** US2 / research R3: a failed (or unparseable) model turn is absorbed into a decline that cites
    * nothing — a `200`, never a `500`. Unlike cap-8 there is no evaluator consumer, so a failure and a
    * decline are indistinguishable here, which is the honest `/cited-ask` contract. */
  @Test
  def aFailedTurnBecomesADeclineThatCitesNothing(): Unit =
    citingModel.whenMessage((_: String) => true).failWith(new RuntimeException("simulated model timeout"))

    val reply = ask("what makes agent work survive a restart without writing persistence code?")

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    assertThat(reply.body().answer).isEqualTo(DocsAgent.DontKnow)
    assertThat(reply.body().citedSources.asJava).isEmpty()
