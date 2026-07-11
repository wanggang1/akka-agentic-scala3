package com.gwgs.akkaagentic.assistant.api

import java.time.Duration

import akka.http.javadsl.model.StatusCodes
import akka.javasdk.testkit.TestModelProvider.AutonomousAgentTools.completeTask
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
