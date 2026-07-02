package com.gwgs.akkaagentic.application

import akka.javasdk.JsonSupport
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

  /** US1: a valid request yields a structured [[GreetingAgent.Result]] with all three
    * fields present. The mocked model returns a fixed JSON `Result`, which the agent
    * deserializes via `responseConformsTo`, so this asserts the structured shape end
    * to end — the greeting text plus the `tone` and `timeOfDay` context fields.
    */
  @Test
  def structuredResponseHasAllFields(): Unit =
    val mocked = GreetingAgent.Result("Hello Ada! Lovely to hear from you.", "casual", "morning")
    greetingModel.fixedResponse(JsonSupport.encodeToString(mocked))

    val result = componentClient
      .forAgent()
      .inSession(UUID.randomUUID().toString)
      .dynamicCall[GreetingAgent.Request, GreetingAgent.Result]("greeting-agent")
      .invoke(GreetingAgent.Request("Ada", "hello there"))

    assertThat(result).isEqualTo(mocked)
    assertThat(result.greeting).isEqualTo("Hello Ada! Lovely to hear from you.")
    assertThat(result.tone).isEqualTo("casual")
    assertThat(result.timeOfDay).isEqualTo("morning")

  /** The greeting adapts to the message's intent rather than using a fixed template.
    * The mocked model keys on the user message text, so a question-style message and a casual
    * one yield distinct structured results — proving the agent forwards the message through to
    * the model. (T008 expands this into the US2 tone/timeOfDay assertions.)
    */
  @Test
  def greetingAdaptsToMessageIntent(): Unit =
    val questionResult = GreetingAgent.Result("Hi Ada — happy to help you reset your password!", "question", "morning")
    val casualResult = GreetingAgent.Result("Hey Ada! Great to see you. 👋", "casual", "morning")

    greetingModel
      .whenMessage((m: String) => m.contains("reset my password"))
      .reply(JsonSupport.encodeToString(questionResult))
    greetingModel
      .whenMessage((m: String) => m.contains("just saying hi"))
      .reply(JsonSupport.encodeToString(casualResult))

    val question = componentClient
      .forAgent()
      .inSession(UUID.randomUUID().toString)
      .dynamicCall[GreetingAgent.Request, GreetingAgent.Result]("greeting-agent")
      .invoke(GreetingAgent.Request("Ada", "How do I reset my password?"))

    val casual = componentClient
      .forAgent()
      .inSession(UUID.randomUUID().toString)
      .dynamicCall[GreetingAgent.Request, GreetingAgent.Result]("greeting-agent")
      .invoke(GreetingAgent.Request("Ada", "just saying hi"))

    assertThat(question).isEqualTo(questionResult)
    assertThat(casual).isEqualTo(casualResult)
    assertThat(question).isNotEqualTo(casual)
