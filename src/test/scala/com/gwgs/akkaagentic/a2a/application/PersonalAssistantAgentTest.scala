package com.gwgs.akkaagentic.a2a.application

import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import akka.javasdk.testkit.TestModelProvider.AiResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Deterministic test for [[PersonalAssistantAgent]] with a mocked model.
  *
  * Proves the agent actually drives the Java [[TodoTools]] → [[TodoEntity]] seam: when the mock replies
  * with a `ToolInvocationRequest`, the runtime invokes the *real* tool (and thus the real entity) and
  * feeds the result back. So an add-then-list in the same session proves persistence end-to-end —
  * offline, and without querying `TodoEntity` from Scala (which the method-ref wall forbids, R2).
  *
  * As in cap-1, the agent is called via `dynamicCall("personal-assistant-agent")`, not a Java method
  * reference (a Scala lambda can't satisfy the SDK's `MethodRefResolver`).
  */
class PersonalAssistantAgentTest extends TestKitSupport:

  private val model = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withModelProvider(classOf[PersonalAssistantAgent], model)

  private def ask(username: String, message: String): String =
    componentClient
      .forAgent()
      .inSession(username) // session id = username, as the endpoint does
      .dynamicCall[PersonalAssistantAgent.Request, String]("personal-assistant-agent")
      .invoke(PersonalAssistantAgent.Request(username, message))

  /** T014: the agent adds a to-do via the tool, and a later `list` in the same session reflects it —
    * so the add genuinely persisted to `TodoEntity` through the tool object.
    */
  @Test
  def addsViaToolAndListReflectsPersistedState(): Unit =
    // Turn shape: user message -> model asks to call a tool -> runtime runs the real tool ->
    // model receives the tool result and produces the final reply.
    model
      .whenMessage((m: String) => m.contains("add"))
      .reply(new TestModelProvider.ToolInvocationRequest("TodoTools_addTodo", """{"description":"buy milk"}"""))
    model
      .whenMessage((m: String) => m.contains("show"))
      .reply(new TestModelProvider.ToolInvocationRequest("TodoTools_listTodos", "{}"))

    model
      .whenToolResult((tr) => tr.name().endsWith("addTodo"))
      .thenReply((tr) => new AiResponse(s"Added it (item ${tr.content()})."))
    model
      .whenToolResult((tr) => tr.name().endsWith("listTodos"))
      .thenReply((tr) => new AiResponse(tr.content())) // relay the rendered list verbatim

    val addReply = ask("alice", "please add buy milk to my list")
    assertThat(addReply).contains("item 1") // add tool returned the new id -> persisted

    val listReply = ask("alice", "show my to-dos")
    // The list reflects the earlier add: proof the entity persisted across the two tool calls.
    assertThat(listReply).contains("buy milk")
