package com.gwgs.akkaagentic.api

import akka.http.javadsl.model.{ContentTypes, StatusCodes}
import akka.javasdk.JsonSupport
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.application.GreetingAgent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Drives [[GreetingEndpoint]] over HTTP with a mocked model (no live model). */
class GreetingEndpointIntegrationTest extends TestKitSupport:

  private val greetingModel = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[GreetingAgent], greetingModel)

  /** US1: a valid request returns 200 with a structured body carrying all three
    * fields. The mocked model returns a JSON `Result`; the endpoint maps it to
    * `GreetReply`, so this asserts the structured contract over HTTP end to end.
    */
  @Test
  def validRequestReturnsStructuredReply(): Unit =
    val mocked = GreetingAgent.Result("Hello Ada! Lovely to hear from you.", "casual", "morning")
    greetingModel.fixedResponse(JsonSupport.encodeToString(mocked))

    val response = httpClient
      .POST("/greet")
      .withRequestBody(GreetingEndpoint.GreetRequest("Ada", "hello there"))
      .responseBodyAs(classOf[GreetingEndpoint.GreetReply])
      .invoke()

    assertThat(response.status()).isEqualTo(StatusCodes.OK)
    val body = response.body()
    assertThat(body.greeting).isEqualTo("Hello Ada! Lovely to hear from you.")
    assertThat(body.tone).isEqualTo("casual")
    assertThat(body.timeOfDay).isEqualTo("morning")

  @Test
  def emptyUserIsRejected(): Unit =
    // No fixedResponse: the model must never be called for invalid input.
    val response = httpClient
      .POST("/greet")
      .withRequestBody(GreetingEndpoint.GreetRequest("", "hello there"))
      .invoke()

    assertThat(response.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  @Test
  def blankTextIsRejected(): Unit =
    val response = httpClient
      .POST("/greet")
      .withRequestBody(GreetingEndpoint.GreetRequest("Ada", "   "))
      .invoke()

    assertThat(response.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  @Test
  def malformedJsonIsRejected(): Unit =
    val response = httpClient
      .POST("/greet")
      .withRequestBody(ContentTypes.APPLICATION_JSON, "{ \"user\": ".getBytes)
      .invoke()

    assertThat(response.status()).isEqualTo(StatusCodes.BAD_REQUEST)
