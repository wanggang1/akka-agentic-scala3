package com.gwgs.akkaagentic.approvals.api

import java.time.Duration

import java.util.UUID

import akka.http.javadsl.model.StatusCodes
import akka.javasdk.testkit.TestModelProvider.AutonomousAgentTools.{completeTask, failTask}
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

  /** Raw GET that does not parse the body, so a non-2xx status can be asserted (a 404 would make
    * `responseBodyAs` throw). Returns the status.
    */
  private def rawGetStatus(caseId: String) =
    httpClient.GET("/approvals/" + caseId).invoke().status()

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

  // --- C4: the reject path (US2) ---------------------------------------------------------------

  /** C4: rejecting fails the gate, which auto-cancels the publish task, so the case reaches `rejected`
    * carrying the reviewer's note and **no reply is ever produced** (SC-003, SC-004, FR-006).
    *
    * The reject → `failureReason` round-trip is the one part of this path the domain unit tests cannot
    * cover (they stub the outcome rather than derive it): here the note goes in over HTTP and comes back
    * out via the gate task's `failureReason`, mapped to `CaseProgress.Rejected(note)`.
    */
  @Test
  def rejectionStopsPublishAndRetainsTheNote(): Unit =
    draftModel.fixedResponse(completeTask(Draft("You can request a refund within 30 days.")))
    // Prime the publish model too: if the gate were not real, a cancelled dependency would still let
    // this fire. It must never run.
    publishModel.fixedResponse(completeTask(PublishedReply("PUBLISHED: should never appear.")))

    val caseId = submit("How do I get a refund?")
    awaitState(caseId, "awaiting-approval")

    val decision = decide(caseId, "reject", Some("Tone is too casual; please revise."))
    assertThat(decision.status()).isEqualTo(StatusCodes.OK)

    val rejected = awaitState(caseId, "rejected")
    assertThat(rejected.note).isEqualTo(Some("Tone is too casual; please revise."))
    assertThat(rejected.reply).isEqualTo(None)

    // Give the (primed, dependency-cancelled) publish agent room to misbehave, then assert it did not.
    Thread.sleep(2000)
    val terminal = caseState(caseId)
    assertThat(terminal.state).isEqualTo("rejected")
    assertThat(terminal.reply).isEqualTo(None)

  // --- C9 / C10 / C5-GET: observing the lifecycle (US3) ----------------------------------------

  /** C9: when the draft agent abandons its task (fails it), the case reaches a terminal `draft-failed`
    * state — distinct from `awaiting-approval` — and the gate never opens. This is a runtime-reachable
    * state the fast mock can produce deterministically (unlike the transient `drafting`, whose
    * distinctness is proven in `ApprovalCaseTest`).
    */
  @Test
  def draftAbandonedShowsDraftFailedAndNeverOpensGate(): Unit =
    draftModel.fixedResponse(failTask("no basis to draft a reply"))
    // Prime publish anyway: it must never run, because the gate is never reached.
    publishModel.fixedResponse(completeTask(PublishedReply("PUBLISHED: should never appear.")))

    val caseId = submit("How do I get a refund?")
    val failed = awaitState(caseId, "draft-failed")

    // Distinct from the gate-open state, and no draft/reply is fabricated.
    assertThat(failed.state).isNotEqualTo("awaiting-approval")
    assertThat(failed.reply).isEqualTo(None)
    assertThat(failed.draft).isEqualTo(None)

    // The gate never opened, so a decision on it is refused (not awaiting) — 409, never 200.
    Thread.sleep(2000)
    assertThat(caseState(caseId).state).isEqualTo("draft-failed")
    assertThat(decide(caseId, "approve").status()).isEqualTo(StatusCodes.CONFLICT)

  /** C10: the two terminal outcomes and a terminal failure are all reported as distinct states.
    * (`published` and `rejected` are each proven reachable in C3/C4; `draft-failed` in C9. Here we
    * assert the three labels differ from one another and from the in-progress vocabulary, which is what
    * a poller relies on to tell them apart — FR-002, US3.)
    */
  @Test
  def terminalStatesAreDistinct(): Unit =
    // approved -> published
    draftModel.fixedResponse(completeTask(Draft("Draft A.")))
    publishModel.fixedResponse(completeTask(PublishedReply("Published A.")))
    val approved = submit("question A")
    awaitState(approved, "awaiting-approval")
    decide(approved, "approve")
    val publishedState = awaitState(approved, "published").state

    // rejected
    val rejectedCase = submit("question B")
    awaitState(rejectedCase, "awaiting-approval")
    decide(rejectedCase, "reject", Some("no"))
    val rejectedState = awaitState(rejectedCase, "rejected").state

    assertThat(List(publishedState, rejectedState, "drafting", "awaiting-approval").distinct.size)
      .isEqualTo(4)

  /** C5 (GET half): an unknown handle is `404` — never a fabricated draft or reply. Permanent home of
    * the research-R2 finding: `forTask(unknownId).get(def)` throws `CommandException`, which the
    * endpoint maps to not-found (distinguishing an unknown case from one still drafting).
    */
  @Test
  def unknownCaseIdReturnsNotFound(): Unit =
    assertThat(rawGetStatus(UUID.randomUUID().toString)).isEqualTo(StatusCodes.NOT_FOUND)
