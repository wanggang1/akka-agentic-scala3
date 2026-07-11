package com.gwgs.akkaagentic.assistant.api

import java.time.Duration
import java.util.UUID

import akka.http.javadsl.model.{ContentTypes, StatusCodes}
import akka.javasdk.testkit.TestModelProvider.AutonomousAgentTools.{completeTask, failTask}
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.assistant.application.{HelpAnswer, HelpDeskAgent}
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{BeforeEach, Test}

/** Drives [[HelpDeskEndpoint]] over HTTP with the agent's model mocked (no live model). */
class HelpDeskEndpointIntegrationTest extends TestKitSupport:

  private val model = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[HelpDeskAgent], model)

  @BeforeEach
  def resetModel(): Unit = model.reset()

  /** C1 + C3 + C6: start returns 202 + Location + taskId; polling yields the answer, and a directly
    * answered question has empty citedTopics.
    */
  @Test
  def startThenPollReturnsTypedAnswer(): Unit =
    model.fixedResponse(
      completeTask(HelpAnswer("We are open 24/7.", "general", java.util.List.of(), 95))
    )

    val accepted = httpClient
      .POST("/help")
      .withRequestBody(HelpDeskEndpoint.AskRequest(Some("What are your support hours?")))
      .responseBodyAs(classOf[HelpDeskEndpoint.StartAccepted])
      .invoke()

    assertThat(accepted.status()).isEqualTo(StatusCodes.ACCEPTED) // C1: 202
    assertThat(accepted.httpResponse().getHeader("Location").isPresent).isTrue() // C1: Location
    val taskId = accepted.body().taskId
    assertThat(taskId).isNotBlank()

    Awaitility
      .await()
      .atMost(Duration.ofSeconds(20))
      .ignoreExceptions() // GET is 404 (responseBodyAs throws) until COMPLETED
      .untilAsserted { () =>
        val reply = httpClient
          .GET("/help/" + taskId)
          .responseBodyAs(classOf[HelpDeskEndpoint.HelpReply])
          .invoke()
        assertThat(reply.status()).isEqualTo(StatusCodes.OK) // C3: 200
        assertThat(reply.body().answer).isEqualTo("We are open 24/7.")
        assertThat(reply.body().category).isEqualTo("general")
        assertThat(reply.body().citedTopics.isEmpty).isTrue() // C6: answered directly
        assertThat(reply.body().confidence).isEqualTo(95)
      }

  /** C5: the agent consults the knowledge base, then completes — citedTopics reflects the lookup. */
  @Test
  def consultsKnowledgeBaseThenAnswers(): Unit =
    model
      .whenMessage(_.contains("password"))
      .reply(new TestModelProvider.ToolInvocationRequest("lookupPolicy", """{"topic":"password-reset"}"""))
    model
      .whenToolResult(_.name() == "lookupPolicy")
      .reply(
        completeTask(
          HelpAnswer(
            "Use \"Forgot password\" on the sign-in page; the link expires in 30 minutes.",
            "account",
            java.util.List.of("password-reset"),
            90
          )
        )
      )

    val accepted = httpClient
      .POST("/help")
      .withRequestBody(HelpDeskEndpoint.AskRequest(Some("How do I reset my password?")))
      .responseBodyAs(classOf[HelpDeskEndpoint.StartAccepted])
      .invoke()

    assertThat(accepted.status()).isEqualTo(StatusCodes.ACCEPTED)
    val taskId = accepted.body().taskId

    Awaitility
      .await()
      .atMost(Duration.ofSeconds(20))
      .ignoreExceptions()
      .untilAsserted { () =>
        val reply = httpClient
          .GET("/help/" + taskId)
          .responseBodyAs(classOf[HelpDeskEndpoint.HelpReply])
          .invoke()
        assertThat(reply.status()).isEqualTo(StatusCodes.OK)
        assertThat(reply.body().category).isEqualTo("account")
        assertThat(reply.body().citedTopics).isEqualTo(List("password-reset")) // C5: cited
      }

  // --- US2: polling an in-progress / unknown / failed task (contracts C2, C4, C7) ---

  /** C2: GET right after POST — before the agent completes — is 404, never a fabricated answer. */
  @Test
  def getBeforeCompletionReturnsNotFound(): Unit =
    // Model left unconfigured: the task can't reach COMPLETED, so the snapshot is not-ready.
    val accepted = httpClient
      .POST("/help")
      .withRequestBody(HelpDeskEndpoint.AskRequest(Some("anything at all")))
      .responseBodyAs(classOf[HelpDeskEndpoint.StartAccepted])
      .invoke()
    assertThat(accepted.status()).isEqualTo(StatusCodes.ACCEPTED)

    // Omit responseBodyAs so a non-2xx status doesn't throw; assert 404 directly.
    val reply = httpClient.GET("/help/" + accepted.body().taskId).invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.NOT_FOUND) // C2

  /** C4: GET on a task id that was never started is 404, with no answer invented. */
  @Test
  def getUnknownIdReturnsNotFound(): Unit =
    val reply = httpClient.GET("/help/" + UUID.randomUUID()).invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.NOT_FOUND) // C4

  /** C7: when the agent abandons the task (fail_task), retrieval is 422 — distinct from 200 and 404. */
  @Test
  def failedTaskReturnsUnprocessable(): Unit =
    model.fixedResponse(failTask("I cannot answer this question."))

    val accepted = httpClient
      .POST("/help")
      .withRequestBody(HelpDeskEndpoint.AskRequest(Some("what is the meaning of life?")))
      .responseBodyAs(classOf[HelpDeskEndpoint.StartAccepted])
      .invoke()
    assertThat(accepted.status()).isEqualTo(StatusCodes.ACCEPTED)
    val taskId = accepted.body().taskId

    Awaitility
      .await()
      .atMost(Duration.ofSeconds(20))
      .untilAsserted { () =>
        val reply = httpClient.GET("/help/" + taskId).invoke()
        assertThat(reply.status()).isEqualTo(StatusCodes.UNPROCESSABLE_ENTITY) // C7
      }

  // --- US4: invalid input rejected before any work (contracts C8, C9) ---

  /** C8: blank and absent questions are each rejected up front — no task started, no model call. */
  @Test
  def blankOrAbsentQuestionRejected(): Unit =
    val blank = httpClient
      .POST("/help")
      .withRequestBody(HelpDeskEndpoint.AskRequest(Some("   ")))
      .invoke()
    assertThat(blank.status()).isEqualTo(StatusCodes.BAD_REQUEST)

    // Absent field: `question` deserializes to None → rejected (400), not a 500.
    val absent = httpClient
      .POST("/help")
      .withRequestBody(ContentTypes.APPLICATION_JSON, "{}".getBytes)
      .invoke()
    assertThat(absent.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  /** C9: a malformed body is rejected by the SDK; an unknown property is tolerated (normal flow). */
  @Test
  def malformedRejectedUnknownPropertyTolerated(): Unit =
    val malformed = httpClient
      .POST("/help")
      .withRequestBody(ContentTypes.APPLICATION_JSON, "{ \"question\": ".getBytes)
      .invoke()
    assertThat(malformed.status()).isEqualTo(StatusCodes.BAD_REQUEST) // C9: malformed

    // Extra property ignored (@JsonIgnoreProperties); the task starts normally → 202.
    val extra = httpClient
      .POST("/help")
      .withRequestBody(ContentTypes.APPLICATION_JSON, """{"question":"hi there","surprise":"ignored"}""".getBytes)
      .invoke()
    assertThat(extra.status()).isEqualTo(StatusCodes.ACCEPTED) // C9: unknown prop tolerated
