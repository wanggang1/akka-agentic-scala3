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

  /** US2 (SC-002, SC-005): the structured result reflects the message's intent, and the
    * time-of-day context is carried through on every response.
    *
    * The mocked model keys on the user message text, returning results with *distinct*
    * `tone` labels for a question-style vs. a casual message. This proves the agent forwards
    * the message to the model and surfaces the model's structured `tone`/`timeOfDay` fields
    * — the real model would derive tone from intent and `timeOfDay` from the time tool; here
    * we assert the plumbing that carries both through.
    */
  @Test
  def intentDrivesToneAndTimeOfDayIsCarried(): Unit =
    val questionResult = GreetingAgent.Result("Hi Ada — happy to help you reset your password!", "question", "afternoon")
    val casualResult = GreetingAgent.Result("Hey Ada! Great to see you. 👋", "casual", "afternoon")

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

    // Intent drives distinct tones.
    assertThat(question.tone).isEqualTo("question")
    assertThat(casual.tone).isEqualTo("casual")
    assertThat(question.tone).isNotEqualTo(casual.tone)

    // Time-of-day context is carried on every structured response.
    assertThat(question.timeOfDay).isEqualTo("afternoon")
    assertThat(casual.timeOfDay).isEqualTo("afternoon")

  /** US2 (T009): the caller's optional timezone actually reaches the model prompt.
    *
    * `whenMessage` matches on the *user message*, which is where `greet` writes the
    * timezone (via `timezoneLine`). Probing on that text proves the plumbing carries a
    * supplied timezone through, and that an absent timezone becomes the UTC-fallback
    * instruction instead of leaking `null`. (This is deterministic; whether the *model*
    * then obeys the instruction is a live-model concern, not unit-testable.)
    */
  @Test
  def timezoneReachesThePrompt(): Unit =
    val zonedResult = GreetingAgent.Result("Good evening, Ada!", "casual", "evening")
    val utcResult = GreetingAgent.Result("Hello Ada!", "casual", "morning")

    greetingModel
      .whenMessage((m: String) => m.contains("America/New_York"))
      .reply(JsonSupport.encodeToString(zonedResult))
    greetingModel
      .whenMessage((m: String) => m.contains("use UTC"))
      .reply(JsonSupport.encodeToString(utcResult))

    val zoned = componentClient
      .forAgent()
      .inSession(UUID.randomUUID().toString)
      .dynamicCall[GreetingAgent.Request, GreetingAgent.Result]("greeting-agent")
      .invoke(GreetingAgent.Request("Ada", "hello there", "America/New_York"))

    // No timezone -> the prompt carries the UTC-fallback instruction, not `null`.
    val fallback = componentClient
      .forAgent()
      .inSession(UUID.randomUUID().toString)
      .dynamicCall[GreetingAgent.Request, GreetingAgent.Result]("greeting-agent")
      .invoke(GreetingAgent.Request("Ada", "hello there"))

    assertThat(zoned).isEqualTo(zonedResult)
    assertThat(fallback).isEqualTo(utcResult)
