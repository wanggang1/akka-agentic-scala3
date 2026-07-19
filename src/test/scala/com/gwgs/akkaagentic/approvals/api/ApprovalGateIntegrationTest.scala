package com.gwgs.akkaagentic.approvals.api

import java.time.Duration

import akka.http.javadsl.model.StatusCodes
import akka.javasdk.testkit.TestModelProvider.AutonomousAgentTools.completeTask
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.approvals.application.{Draft, DraftAgent, PublishAgent, PublishedReply}
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{BeforeEach, Test}

/** Drives the whole gated flow over HTTP with both agents' models mocked (no live model, no key).
  *
  * This is where the capability's central claim is checked end to end: **the publish agent is assigned
  * its task at submit time, yet produces nothing until a human approves.** Nothing in our code
  * sequences that — the runtime withholds the publish task because it depends on the unassigned
  * approval task.
  *
  * It is also the first test that actually *runs* `DraftAgent` and `PublishAgent`, so it doubles as the
  * proof that both are registered in the hand-maintained component descriptor.
  *
  * The state-machine combinatorics are not re-tested here — they are asserted directly and far faster in
  * `ApprovalCaseTest`. This test covers only what needs a real task chain: the wiring, the dependency
  * gate, and the HTTP contract. Everything is Scala, including the human decision (research R1/R6) —
  * the contrast with cap-4, which needed a Java test to read `SessionMemoryEntity`.
  */
class ApprovalGateIntegrationTest extends TestKitSupport:

  private val draftModel = new TestModelProvider()
  private val publishModel = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[DraftAgent], draftModel)
      .withModelProvider(classOf[PublishAgent], publishModel)

  @BeforeEach
  def resetModels(): Unit =
    draftModel.reset()
    publishModel.reset()

  // --- helpers ---------------------------------------------------------------------------------

  private def submit(question: String): String =
    val response = httpClient
      .POST("/approvals")
      .withRequestBody(ApprovalEndpoint.SubmitRequest(Some(question)))
      .responseBodyAs(classOf[ApprovalEndpoint.CaseAccepted])
      .invoke()
    assertThat(response.status()).isEqualTo(StatusCodes.ACCEPTED)
    response.body().caseId

  private def caseState(caseId: String): ApprovalEndpoint.CaseState =
    httpClient
      .GET("/approvals/" + caseId)
      .responseBodyAs(classOf[ApprovalEndpoint.CaseState])
      .invoke()
      .body()

  /** Poll until the case reaches `state`, then return that payload. */
  private def awaitState(caseId: String, state: String): ApprovalEndpoint.CaseState =
    Awaitility
      .await()
      .atMost(Duration.ofSeconds(20))
      .ignoreExceptions()
      .until(() => caseState(caseId), (s: ApprovalEndpoint.CaseState) => s.state == state)

  private def decide(caseId: String, decision: String, note: Option[String] = None) =
    httpClient
      .POST(s"/approvals/$caseId/$decision")
      .withRequestBody(ApprovalEndpoint.DecisionRequest(note))
      .invoke()

  // --- C1 / C2 / C3: the approve path ----------------------------------------------------------

  /** C1: a valid submission is acknowledged immediately with a handle and a `Location`, before any
    * draft exists.
    */
  @Test
  def submitReturnsAcceptedWithHandleAndLocation(): Unit =
    draftModel.fixedResponse(completeTask(Draft("A drafted reply.")))

    val response = httpClient
      .POST("/approvals")
      .withRequestBody(ApprovalEndpoint.SubmitRequest(Some("How do I get a refund?")))
      .responseBodyAs(classOf[ApprovalEndpoint.CaseAccepted])
      .invoke()

    assertThat(response.status()).isEqualTo(StatusCodes.ACCEPTED)
    assertThat(response.body().caseId).isNotBlank()
    assertThat(response.httpResponse().getHeader("Location").get().value())
      .isEqualTo("/approvals/" + response.body().caseId)

  /** C2 (**the core assertion**): once the draft completes, the case waits at the gate showing the
    * draft — and **no reply exists**, because the publish task is held back by its dependency even
    * though its agent was assigned at submit time (SC-003, FR-007).
    */
  @Test
  def gateHoldsPublishUntilAHumanApproves(): Unit =
    draftModel.fixedResponse(completeTask(Draft("You can request a refund within 30 days.")))
    publishModel.fixedResponse(completeTask(PublishedReply("PUBLISHED: refunds within 30 days.")))

    val caseId = submit("How do I get a refund?")
    val awaiting = awaitState(caseId, "awaiting-approval")

    assertThat(awaiting.draft).isEqualTo(Some("You can request a refund within 30 days."))
    assertThat(awaiting.reply).isEqualTo(None) // nothing published — the gate is doing its job

    // The publish model is primed and its agent is assigned, so if the gate were not real the reply
    // would appear on its own. Give it room to misbehave, then assert it still has not.
    Thread.sleep(2000)
    val stillWaiting = caseState(caseId)
    assertThat(stillWaiting.state).isEqualTo("awaiting-approval")
    assertThat(stillWaiting.reply).isEqualTo(None)

  /** C3: approving releases the publish task; the case reaches `published` with the final reply. */
  @Test
  def approvalReleasesPublish(): Unit =
    draftModel.fixedResponse(completeTask(Draft("You can request a refund within 30 days.")))
    publishModel.fixedResponse(completeTask(PublishedReply("PUBLISHED: refunds within 30 days.")))

    val caseId = submit("How do I get a refund?")
    awaitState(caseId, "awaiting-approval")

    val decision = decide(caseId, "approve", Some("Looks good."))
    assertThat(decision.status()).isEqualTo(StatusCodes.OK)

    val published = awaitState(caseId, "published")
    assertThat(published.reply).isEqualTo(Some("PUBLISHED: refunds within 30 days."))
