package com.gwgs.akkaagentic.api

import akka.http.javadsl.model.StatusCodes
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

  @Test
  def validRequestReturnsGreeting(): Unit =
    val mocked = "Hello Ada! Lovely to hear from you."
    greetingModel.fixedResponse(mocked)

    val response = httpClient
      .POST("/greet")
      .withRequestBody(GreetingEndpoint.GreetRequest("Ada", "hello there"))
      .responseBodyAs(classOf[GreetingEndpoint.GreetReply])
      .invoke()

    assertThat(response.status()).isEqualTo(StatusCodes.OK)
    assertThat(response.body().greeting).isEqualTo(mocked)
