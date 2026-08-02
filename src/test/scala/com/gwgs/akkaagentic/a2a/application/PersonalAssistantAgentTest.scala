package com.gwgs.akkaagentic.a2a.application

import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import akka.javasdk.testkit.TestModelProvider.AiResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

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

  @BeforeEach
  def resetModel(): Unit = model.reset()

  private def ask(username: String, message: String): String =
    invoke(PersonalAssistantAgent.Request(username, message)) // delegated defaults false (top-level)

  private def invoke(req: PersonalAssistantAgent.Request): String =
    componentClient
      .forAgent()
      .inSession(req.username) // session id = username, as the endpoint does
      .dynamicCall[PersonalAssistantAgent.Request, String]("personal-assistant-agent")
      .invoke(req)

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

  /** T022 (US2): a top-level request can delegate — alice's assistant forwards to bob's assistant (a real
    * nested agent call via `ForwardTool` → agent `dynamicCall`), and relays bob's reply with attribution.
    */
  @Test
  def topLevelRequestDelegatesToAnotherAssistant(): Unit =
    // alice (top-level) is told to contact bob -> calls the forward tool targeting bob with "GREET".
    model
      .whenMessage((m: String) => m.contains("contact bob"))
      .reply(new TestModelProvider.ToolInvocationRequest("ForwardTool_askAssistant", """{"username":"bob","question":"GREET"}"""))
    // bob (the delegate) receives "GREET" and simply answers.
    model
      .whenMessage((m: String) => m.contains("GREET"))
      .reply(new AiResponse("Hi there!"))
    // alice relays the forward tool's result (which ForwardTool prefixes with "bob's assistant:").
    model
      .whenToolResult((tr) => tr.name().endsWith("askAssistant"))
      .thenReply((tr) => new AiResponse(tr.content()))

    val reply = ask("alice", "please contact bob")
    assertThat(reply).contains("bob's assistant:") // attribution added by ForwardTool
    assertThat(reply).contains("Hi there!") // bob's actual reply, relayed to alice

  /** T022 (US2, SC-004): the one-hop guard — a request that arrives *as a delegate* (`delegated = true`)
    * is offered no forward tool, so an attempt to delegate onward cannot be dispatched. Since the forward
    * tool is absent, the model's (scripted) forward call cannot be fulfilled; the agent's `.onFailure`
    * degrades that to a clean fallback reply rather than a raw error — and crucially, no onward delegation
    * happens (the reply never carries carol's assistant's content).
    */
  @Test
  def delegatedRequestCannotDelegateOnward(): Unit =
    // Script the model to (try to) forward — but a delegated call has no forward tool registered.
    model
      .whenMessage((m: String) => m.contains("contact carol"))
      .reply(new TestModelProvider.ToolInvocationRequest("ForwardTool_askAssistant", """{"username":"carol","question":"GREET"}"""))
    // If the guard leaked and carol WERE reached, this is what she'd say — it must never surface.
    model
      .whenMessage((m: String) => m.contains("GREET"))
      .reply(new AiResponse("carol-was-reached"))

    val reply = invoke(PersonalAssistantAgent.Request("bob", "please contact carol", delegated = true))

    assertThat(reply).contains("try again") // graceful FailureReply — the forward tool was not available
    assertThat(reply).doesNotContain("carol-was-reached") // the one-hop guard held: no onward delegation
