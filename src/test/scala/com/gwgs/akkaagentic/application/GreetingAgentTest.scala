package com.gwgs.akkaagentic.application

import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

import java.util.UUID

/** Deterministic unit test for [[GreetingAgent]] using a mocked model.
  *
  * Note on Scala interop: the Java `componentClient.forAgent()...method(Agent::greet)`
  * form relies on a Java method-reference (`SerializedLambda`) that a Scala lambda does
  * not reproduce. We use `dynamicCall("greeting-agent")` instead — it resolves the
  * agent's single handler by component id, which works the same from Scala and from the
  * endpoint.
  */
class GreetingAgentTest extends TestKitSupport:

  private val greetingModel = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[GreetingAgent], greetingModel)

  @Test
  def replyWithFixedResponse(): Unit =
    val mocked = "Hello Ada! Lovely to hear from you."
    greetingModel.fixedResponse(mocked)

    val reply = componentClient
      .forAgent()
      .inSession(UUID.randomUUID().toString)
      .dynamicCall[GreetingAgent.Request, String]("greeting-agent")
      .invoke(GreetingAgent.Request("Ada", "hello there"))

    assertThat(reply).isEqualTo(mocked)
