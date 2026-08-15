package com.gwgs.akkaagentic.activities.api

import java.time.Duration
import java.util.UUID

import akka.http.javadsl.model.{ContentTypes, StatusCodes}
import akka.javasdk.testkit.TestModelProvider.AutonomousAgentTools.{completeTask, failTask}
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.activities.application.{ActivityCoordinator, ActivitySuggestion}
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{BeforeEach, Test}

/** Drives [[ActivityEndpoint]] over HTTP with the coordinator's model mocked (no live model).
  *
  * Covers the start-then-poll **contract** (US1 T014) and the validation-first / poll semantics (US3):
  * `202` + `Location` on start, `400` for blank/absent/malformed input, `404` for not-ready/unknown, and
  * `422` for a failed task. The delegation + synthesis path is covered live (research D9); here the
  * coordinator completes directly (a valid `complete_task`), the same reason as
  * [[com.gwgs.akkaagentic.activities.application.ActivityCoordinatorIntegrationTest]].
  */
class ActivityEndpointIntegrationTest extends TestKitSupport:

  private val coordinator = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[ActivityCoordinator], coordinator)

  @BeforeEach
  def reset(): Unit = coordinator.reset()

  private def suggestion =
    completeTask(
      ActivitySuggestion("Walk the Esplanade.", "Clear skies, around 20°C.", java.util.List.of("weather-specialist"))
    )

  /** US1/T014: a valid request returns `202` + `Location` + a task id. */
  @Test
  def startReturns202WithLocationAndTaskId(): Unit =
    coordinator.fixedResponse(suggestion)
    val accepted = httpClient
      .POST("/activities")
      .withRequestBody(ActivityEndpoint.StartRequest(Some("Boston"), Some("outdoorsy")))
      .responseBodyAs(classOf[ActivityEndpoint.StartAccepted])
      .invoke()
    assertThat(accepted.status()).isEqualTo(StatusCodes.ACCEPTED)
    assertThat(accepted.httpResponse().getHeader("Location").isPresent).isTrue()
    assertThat(accepted.body().taskId).isNotBlank()

  /** US3: a blank location is rejected up front — `400`, no task started. */
  @Test
  def blankLocationRejected(): Unit =
    val reply = httpClient
      .POST("/activities")
      .withRequestBody(ActivityEndpoint.StartRequest(Some("   "), None))
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  /** US3: an absent `location` field deserializes to `None` → `400`, not a `500`. */
  @Test
  def absentLocationRejected(): Unit =
    val reply = httpClient
      .POST("/activities")
      .withRequestBody(ContentTypes.APPLICATION_JSON, "{}".getBytes)
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  /** US3: a malformed JSON body is rejected by the SDK boundary → `400`. */
  @Test
  def malformedBodyRejected(): Unit =
    val reply = httpClient
      .POST("/activities")
      .withRequestBody(ContentTypes.APPLICATION_JSON, "{ \"location\": ".getBytes)
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  /** US3: an unknown extra property alongside a valid location is tolerated → `202`. */
  @Test
  def unknownPropertyTolerated(): Unit =
    coordinator.fixedResponse(suggestion)
    val reply = httpClient
      .POST("/activities")
      .withRequestBody(ContentTypes.APPLICATION_JSON, """{"location":"Boston","surprise":"ignored"}""".getBytes)
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.ACCEPTED)

  /** US3: polling right after start — before completion — is `404`, never a fabricated suggestion. */
  @Test
  def getBeforeCompletionReturnsNotFound(): Unit =
    // Model left unconfigured: the task can't reach COMPLETED, so the snapshot is not-ready.
    val accepted = httpClient
      .POST("/activities")
      .withRequestBody(ActivityEndpoint.StartRequest(Some("Boston"), None))
      .responseBodyAs(classOf[ActivityEndpoint.StartAccepted])
      .invoke()
    val reply = httpClient.GET("/activities/" + accepted.body().taskId).invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.NOT_FOUND)

  /** US3: polling a task id that was never started is `404`. */
  @Test
  def getUnknownIdReturnsNotFound(): Unit =
    val reply = httpClient.GET("/activities/" + UUID.randomUUID()).invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.NOT_FOUND)

  /** US3 (FR-009): a task the coordinator cannot complete polls as `422` — distinct from `404`/`200`. */
  @Test
  def failedTaskReturnsUnprocessable(): Unit =
    coordinator.fixedResponse(failTask("No suitable activities could be found."))
    val accepted = httpClient
      .POST("/activities")
      .withRequestBody(ActivityEndpoint.StartRequest(Some("Boston"), None))
      .responseBodyAs(classOf[ActivityEndpoint.StartAccepted])
      .invoke()
    val taskId = accepted.body().taskId

    Awaitility
      .await()
      .atMost(Duration.ofSeconds(20))
      .untilAsserted { () =>
        val reply = httpClient.GET("/activities/" + taskId).invoke()
        assertThat(reply.status()).isEqualTo(StatusCodes.UNPROCESSABLE_ENTITY)
      }
