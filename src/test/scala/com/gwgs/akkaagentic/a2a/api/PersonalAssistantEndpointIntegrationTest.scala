package com.gwgs.akkaagentic.a2a.api

import akka.http.javadsl.model.StatusCodes
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import akka.javasdk.testkit.TestModelProvider.AiResponse
import com.gwgs.akkaagentic.a2a.application.PersonalAssistantAgent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

/** Drives [[PersonalAssistantEndpoint]] over HTTP with the agent's model mocked (no live model).
  *
  * US1 coverage: the synchronous `200` happy path (a to-do added through the real tool over HTTP,
  * reply echoing the path username) and the validation guardrail. Delegation (A→B) is added in the US2
  * step; malformed-body/unknown-property in the US4 step. Recall is live-only (research R6), not here.
  */
class PersonalAssistantEndpointIntegrationTest extends TestKitSupport:

  private val model = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[PersonalAssistantAgent], model)

  @BeforeEach
  def resetModel(): Unit = model.reset()

  /** US1 happy path: a valid request drives the add tool (real `TodoEntity`) and returns `200` with the
    * reply and the echoed username. Proves the endpoint → agent → TodoTools → entity path over HTTP.
    */
  @Test
  def validRequestAddsTodoAndReturnsOk(): Unit =
    model
      .whenMessage((m: String) => m.contains("add"))
      .reply(new TestModelProvider.ToolInvocationRequest("TodoTools_addTodo", """{"description":"buy milk"}"""))
    model
      .whenToolResult((tr) => tr.name().endsWith("addTodo"))
      .thenReply((tr) => new AiResponse(s"Added it (item ${tr.content()})."))

    val reply = httpClient
      .POST("/request/alice")
      .withRequestBody(PersonalAssistantEndpoint.RequestBody(Some("please add buy milk")))
      .responseBodyAs(classOf[PersonalAssistantEndpoint.Reply])
      .invoke()

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    assertThat(reply.body().username).isEqualTo("alice") // echoes the path
    assertThat(reply.body().reply).contains("item 1") // the add tool ran against the real entity

  private def post(username: String, message: String): String =
    httpClient
      .POST("/request/" + username)
      .withRequestBody(PersonalAssistantEndpoint.RequestBody(Some(message)))
      .responseBodyAs(classOf[PersonalAssistantEndpoint.Reply])
      .invoke()
      .body()
      .reply

  /** T023 (US2, SC-002): amy delegates to ben's assistant to add a to-do; the effect lands under **ben**,
    * and amy's reply relays ben's confirmation. Proves A→B delegation over HTTP and per-user isolation of
    * the effect — end to end through the real ForwardTool → nested agent → ben's TodoEntity.
    */
  @Test
  def delegationLandsUnderTargetUser(): Unit =
    // amy (top-level) is told to delegate to ben -> forwards with a request to add a slide to-do.
    model
      .whenMessage((m: String) => m.contains("delegate to ben"))
      .reply(new TestModelProvider.ToolInvocationRequest("ForwardTool_askAssistant", """{"username":"ben","question":"PLEASE_ADD_SLIDES"}"""))
    // ben (the delegate) turns that into an add on his own list.
    model
      .whenMessage((m: String) => m.contains("PLEASE_ADD_SLIDES"))
      .reply(new TestModelProvider.ToolInvocationRequest("TodoTools_addTodo", """{"description":"prepare slides"}"""))
    // a "list" request lists the caller's own to-dos.
    model
      .whenMessage((m: String) => m.contains("list"))
      .reply(new TestModelProvider.ToolInvocationRequest("TodoTools_listTodos", "{}"))
    model
      .whenToolResult((tr) => tr.name().endsWith("addTodo"))
      .thenReply((tr) => new AiResponse(s"Added prepare slides (item ${tr.content()})."))
    model
      .whenToolResult((tr) => tr.name().endsWith("listTodos"))
      .thenReply((tr) => new AiResponse(tr.content()))
    model
      .whenToolResult((tr) => tr.name().endsWith("askAssistant"))
      .thenReply((tr) => new AiResponse(tr.content()))

    val amyReply = post("amy", "please delegate to ben")
    assertThat(amyReply).contains("ben's assistant:") // relayed with attribution
    assertThat(amyReply).contains("prepare slides") // ben's confirmation reached amy

    // The to-do landed under ben...
    assertThat(post("ben", "list my to-dos")).contains("prepare slides")
    // ...and NOT under amy (per-user isolation of the delegated effect).
    assertThat(post("amy", "list my to-dos")).doesNotContain("prepare slides")

  /** T026 (US3, SC-005): per-user isolation — a to-do added under `alice` is invisible to `carol`.
    * Each username is its own session (memory) and its own `TodoEntity` key, so lists never bleed across
    * users. Recall (the model *using* prior turns) is not asserted here — mock never sees replayed history
    * (research R6); it is proven by the live smoke test.
    */
  @Test
  def todosAreIsolatedPerUser(): Unit =
    model
      .whenMessage((m: String) => m.contains("add"))
      .reply(new TestModelProvider.ToolInvocationRequest("TodoTools_addTodo", """{"description":"alice-secret"}"""))
    model
      .whenMessage((m: String) => m.contains("list"))
      .reply(new TestModelProvider.ToolInvocationRequest("TodoTools_listTodos", "{}"))
    model
      .whenToolResult((tr) => tr.name().endsWith("addTodo"))
      .thenReply((tr) => new AiResponse(s"Added it (item ${tr.content()})."))
    model
      .whenToolResult((tr) => tr.name().endsWith("listTodos"))
      .thenReply((tr) => new AiResponse(tr.content()))

    // Distinct usernames — TodoEntity state persists across methods in the shared runtime, so isolate
    // from the "alice" used by validRequestAddsTodoAndReturnsOk.
    post("iso-alice", "please add something") // lands under iso-alice
    assertThat(post("iso-alice", "list my to-dos")).contains("alice-secret") // iso-alice sees it
    assertThat(post("iso-carol", "list my to-dos")).doesNotContain("alice-secret") // iso-carol does not

  /** Graceful degradation: a model failure (e.g. the qwen3 null-content tool-call quirk, README cap-6
    * "Live caveat") is caught by the agent's `.onFailure` and returned as a clean `200` fallback reply,
    * not a raw `500`. Proves one bad model turn degrades to a friendly message instead of an error.
    */
  @Test
  def modelFailureDegradesToFallbackReply(): Unit =
    model
      .whenMessage((m: String) => m.contains("boom"))
      .failWith(new RuntimeException("simulated model failure"))

    val reply = httpClient
      .POST("/request/frank")
      .withRequestBody(PersonalAssistantEndpoint.RequestBody(Some("boom")))
      .responseBodyAs(classOf[PersonalAssistantEndpoint.Reply])
      .invoke()

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    assertThat(reply.body().username).isEqualTo("frank")
    assertThat(reply.body().reply).contains("try again") // the FailureReply fallback, not a 500

  /** Validation: a blank message is rejected up front — `400`, assistant never engaged. */
  @Test
  def blankMessageRejected(): Unit =
    // Omit responseBodyAs so a non-2xx status doesn't throw; assert 400 directly.
    val reply = httpClient
      .POST("/request/alice")
      .withRequestBody(PersonalAssistantEndpoint.RequestBody(Some("   ")))
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  /** Validation: an absent `message` field deserializes to `None` → `400`, not a `500`. */
  @Test
  def absentMessageRejected(): Unit =
    val reply = httpClient
      .POST("/request/alice")
      .withRequestBody(akka.http.javadsl.model.ContentTypes.APPLICATION_JSON, "{}".getBytes)
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  /** T028 (US4): a malformed JSON body is rejected by the SDK boundary → `400` (assistant never engaged). */
  @Test
  def malformedBodyRejected(): Unit =
    val reply = httpClient
      .POST("/request/alice")
      .withRequestBody(akka.http.javadsl.model.ContentTypes.APPLICATION_JSON, "{ \"message\": ".getBytes)
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  /** T028 (US4): an unknown extra property alongside a valid message is tolerated → `200`
    * (`RequestBody` is `@JsonIgnoreProperties`).
    */
  @Test
  def unknownPropertyTolerated(): Unit =
    model.fixedResponse("ok")
    val reply = httpClient
      .POST("/request/alice")
      .withRequestBody(akka.http.javadsl.model.ContentTypes.APPLICATION_JSON, """{"message":"hi","surprise":"ignored"}""".getBytes)
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
