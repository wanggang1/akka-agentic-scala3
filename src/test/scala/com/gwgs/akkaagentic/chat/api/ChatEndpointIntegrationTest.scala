package com.gwgs.akkaagentic.chat.api

import java.util.UUID

import akka.http.javadsl.model.{ContentTypes, StatusCodes}
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import com.gwgs.akkaagentic.chat.application.ChatAgent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

/** Drives [[ChatEndpoint]] over HTTP with the agent's model mocked (no live model).
  *
  * Covers the offline-provable HTTP contract: the synchronous `200` reply (echoes the path `sessionId`,
  * carries the reply) and the validation guardrail. The memory *behavior* (recall, isolation) is not
  * asserted here — the mock never sees replayed history (research R6); retention/isolation are proven by
  * `SessionMemoryIntegrationTest`, and recall by the live smoke test.
  */
class ChatEndpointIntegrationTest extends TestKitSupport:

  private val model = new TestModelProvider()

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[ChatAgent], model)

  @BeforeEach
  def resetModel(): Unit = model.reset()

  /** C7: a valid message returns `200` with the mocked reply and echoes the path `sessionId`. */
  @Test
  def validMessageReturnsOkWithReplyAndEchoedSessionId(): Unit =
    model.fixedResponse("Hello there!")
    val session = UUID.randomUUID().toString

    val reply = httpClient
      .POST("/chat/" + session)
      .withRequestBody(ChatEndpoint.ChatRequest(Some("hi")))
      .responseBodyAs(classOf[ChatEndpoint.ChatReply])
      .invoke()

    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
    assertThat(reply.body().sessionId).isEqualTo(session) // echoes the path id
    assertThat(reply.body().reply).isEqualTo("Hello there!")

  /** C3: a blank message is rejected up front — `400`, assistant never engaged. */
  @Test
  def blankMessageRejected(): Unit =
    // Omit responseBodyAs so a non-2xx status doesn't throw; assert 400 directly.
    val reply = httpClient
      .POST("/chat/" + UUID.randomUUID())
      .withRequestBody(ChatEndpoint.ChatRequest(Some("   ")))
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  /** C4: an absent `message` field deserializes to `None` → `400`, not a `500`. */
  @Test
  def absentMessageRejected(): Unit =
    val reply = httpClient
      .POST("/chat/" + UUID.randomUUID())
      .withRequestBody(ContentTypes.APPLICATION_JSON, "{}".getBytes)
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  /** C5: a malformed JSON body is rejected by the SDK boundary → `400`. */
  @Test
  def malformedBodyRejected(): Unit =
    val reply = httpClient
      .POST("/chat/" + UUID.randomUUID())
      .withRequestBody(ContentTypes.APPLICATION_JSON, "{ \"message\": ".getBytes)
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)

  /** C6: an unknown extra property alongside a valid message is tolerated → `200`. */
  @Test
  def unknownPropertyTolerated(): Unit =
    model.fixedResponse("ok")
    val reply = httpClient
      .POST("/chat/" + UUID.randomUUID())
      .withRequestBody(ContentTypes.APPLICATION_JSON, """{"message":"hi","surprise":"ignored"}""".getBytes)
      .invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.OK)
