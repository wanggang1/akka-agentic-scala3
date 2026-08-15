package com.gwgs.akkaagentic.activities.application

import java.time.Duration

import scala.jdk.CollectionConverters.*

import akka.http.javadsl.model.StatusCodes
import akka.javasdk.testkit.TestModelProvider.AutonomousAgentTools.completeTask
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.activities.api.ActivityEndpoint
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{BeforeEach, Test}

/** Offline test that the coordinator accepts a `SUGGEST` task and produces the typed
  * [[ActivitySuggestion]], surfaced through the endpoint (start-then-poll → 200).
  *
  * NOTE (research D9): request-based **delegation is NOT faithfully mockable** in the SDK 3.6.0 testkit —
  * `AutonomousAgentTools.delegateTo(Class, String)` delivers a generic `json.akka.io/object` payload the
  * request-based worker cannot deserialize (to a `String` OR a record; both fail with
  * `Could not deserialize [json.akka.io/object]`). Live works because the real runtime tags the payload
  * with the worker's type (proven in T006 via `WeatherData`'s un-hallucinatable canned default). So this
  * offline test asserts the coordinator's task → typed-result → endpoint path with a **direct** completion;
  * the delegation + synthesis itself is proven **live** (T006/T011 and the live smoke). `consultedSpecialists`
  * is model-authored, so offline it is exactly what we script (research D6). Revisiting offline delegation
  * mocking on a newer SDK is a logged TODO (research D9).
  */
class ActivityCoordinatorIntegrationTest extends TestKitSupport:

  private val coordinator = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[ActivityCoordinator], coordinator)

  @BeforeEach
  def reset(): Unit = coordinator.reset()

  @Test
  def coordinatorProducesTypedSuggestionThroughEndpoint(): Unit =
    coordinator.fixedResponse(
      completeTask(
        ActivitySuggestion(
          "Under clear skies near 20°C, take the kids to Franklin Park or walk the Esplanade.",
          "Clear skies, around 20°C.",
          java.util.List.of("weather-specialist", "activity-specialist")
        )
      )
    )

    val taskId = httpClient
      .POST("/activities")
      .withRequestBody(ActivityEndpoint.StartRequest(Some("Boston"), Some("outdoorsy, with kids")))
      .responseBodyAs(classOf[ActivityEndpoint.StartAccepted])
      .invoke()
      .body()
      .taskId

    Awaitility
      .await()
      .atMost(Duration.ofSeconds(20))
      .ignoreExceptions() // GET is 404 (responseBodyAs throws) until COMPLETED
      .untilAsserted { () =>
        val reply = httpClient
          .GET("/activities/" + taskId)
          .responseBodyAs(classOf[ActivityEndpoint.SuggestionReply])
          .invoke()
        assertThat(reply.status()).isEqualTo(StatusCodes.OK)
        assertThat(reply.body().suggestion).contains("Franklin Park")
        assertThat(reply.body().weatherConsidered).isEqualTo(Some("Clear skies, around 20°C."))
        assertThat(reply.body().consultedSpecialists.asJava)
          .containsExactlyInAnyOrder("weather-specialist", "activity-specialist")
      }
