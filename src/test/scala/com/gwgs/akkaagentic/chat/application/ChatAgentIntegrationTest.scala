package com.gwgs.akkaagentic.chat.application

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*

import akka.javasdk.testkit.TestModelProvider.{AiResponse, InputMessage}
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

/** Drives [[ChatAgent]] with a mocked model (no live model).
  *
  * These tests cover what is provable **offline**: the `dynamicCall` + `.inSession` wiring, and the
  * exact input the mock receives. They deliberately do NOT assert multi-turn recall — see below.
  *
  * ==Research R6 (resolved here)==
  * With `TestModelProvider`, the mocked model receives **only the current turn's user message** — the
  * SDK does not assemble session history (nor the system prompt) into a test model provider's input.
  * (Verified: across two turns on one session id, each model call gets a size-1 message list; a 2s gap
  * between turns changes nothing, so it is not a write/read race.) Consequently, multi-turn **recall**
  * (US1) and **isolation** (US2) are not observable through the mock and are proven instead by the live
  * Gemini smoke test (tasks T018). The Scala escape hatch — querying `SessionMemoryEntity` to prove
  * retention — is also blocked: the EventSourcedEntity client has no `dynamicCall` (only Java
  * method-refs), the same wall as cap-2's `WorkflowClient`, so it would require Java. Session memory is
  * thus Scala-friendly to *use*, but its effect is invisible to the offline mock.
  */
class ChatAgentIntegrationTest extends TestKitSupport:

  private val model = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[ChatAgent], model)

  @BeforeEach
  def resetModel(): Unit = model.reset()

  private def chat(sessionId: String, message: String): String =
    componentClient
      .forAgent()
      .inSession(sessionId)
      .dynamicCall[String, String]("chat-agent")
      .invoke(message)

  /** Wiring: a Scala agent called by component id, in a caller-supplied session, returns the reply.
    * Two turns on one session both succeed (the plumbing US1 rides on), independent of memory.
    */
  @Test
  def eachTurnOnASessionReturnsTheReply(): Unit =
    model.fixedResponse("Sure — happy to chat.")

    val session = UUID.randomUUID().toString
    assertThat(chat(session, "hello")).isEqualTo("Sure — happy to chat.")
    assertThat(chat(session, "still there?")).isEqualTo("Sure — happy to chat.")

  /** R6 regression: pin that the mock sees ONLY the current turn's message — no replayed history, no
    * system prompt. If a future SDK version starts replaying history to test providers, this flips and
    * we should strengthen the offline suite (and revisit US1/US2 offline coverage).
    */
  @Test
  def mockReceivesOnlyTheCurrentTurnNotReplayedHistory(): Unit =
    val inputs = new ConcurrentLinkedQueue[String]()
    model.withMessageSelector { (msgs: java.util.List[InputMessage]) =>
      inputs.add(msgs.asScala.map(_.content).mkString(" ~~ "))
      msgs.get(msgs.size - 1)
    }
    model.fixedResponse((_: InputMessage) => AiResponse("ok"))

    val session = UUID.randomUUID().toString
    chat(session, "my name is Ada")
    chat(session, "what is my name?")

    // Each call carries exactly its own turn's message — turn 2 does NOT contain turn 1's fact.
    assertThat(inputs).containsExactly("my name is Ada", "what is my name?")
