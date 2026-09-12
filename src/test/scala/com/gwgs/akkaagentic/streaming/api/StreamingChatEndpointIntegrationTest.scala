package com.gwgs.akkaagentic.streaming.api

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import akka.http.javadsl.Http
import akka.http.javadsl.model.{ContentTypes, HttpRequest, StatusCodes}
import akka.javasdk.testkit.{TestKit, TestKitSupport, TestModelProvider}
import akka.stream.javadsl.Sink
import akka.util.ByteString
import com.gwgs.akkaagentic.streaming.application.StreamingChatAgent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{BeforeEach, Test}

/** T008 — the streaming surface over HTTP, driven end to end with a scripted model.
  *
  * **This test is Scala**, and that is the point worth noticing: the method-reference wall claims only
  * the class that *holds* a `tokenStream` method reference (the endpoint). `httpClient` holds none, so
  * the capability's own surface test stays in the project's primary language — exactly the outcome
  * capability 11 reached for its View endpoint test.
  *
  * Written before `StreamingChatEndpoint` exists, so it fails first (Constitution III).
  */
class StreamingChatEndpointIntegrationTest extends TestKitSupport:

  private val model = new TestModelProvider()

  /** Long enough to arrive in pieces: a one-word reply would make "streamed" and "not streamed"
    * indistinguishable, which is what SC-001 is about. */
  private val Answer =
    "The runtime persists the task and the agent's process state as the loop runs. " +
      "That is why work survives a restart without any persistence code of your own."

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withModelProvider(classOf[StreamingChatAgent], model)

  @BeforeEach
  def reset(): Unit =
    model.reset()
    model.fixedResponse(Answer)

  private def post(sessionId: String, message: String) =
    httpClient
      .POST(s"/stream-chat/$sessionId")
      .withRequestBody(StreamingChatEndpointIntegrationTest.Body(message))
      .invoke()

  /** SC-002 parity — the fragments, reassembled, are exactly the answer: nothing added, reordered or
    * lost. The testkit's client buffers the body, which is precisely what makes it the right tool for
    * *this* assertion (and the wrong one for incrementality — see the chunking test). */
  @Test
  def theStreamedBodyReassemblesToExactlyTheAnswer(): Unit =
    val response = post("c-parity", "why does work survive a restart?")

    assertThat(response.status()).isEqualTo(StatusCodes.OK)
    assertThat(response.body().utf8String).isEqualTo(Answer)

  /** SC-003 — validation runs first: a blank question is rejected before the agent is engaged, as an
    * ordinary non-streamed response. Note no `responseBodyAs`: it throws on a non-2xx status (the
    * project's httpClient failure-status pattern). */
  @Test
  def aBlankQuestionIsRejectedBeforeAnyModelCall(): Unit =
    val response = httpClient
      .POST("/stream-chat/c-blank")
      .withRequestBody(StreamingChatEndpointIntegrationTest.Body("   "))
      .invoke()

    assertThat(response.status()).isEqualTo(StatusCodes.BAD_REQUEST)
    assertThat(response.body().utf8String).contains("question must not be blank")

  /** SC-001 incrementality — the answer really does arrive in pieces **over the wire**.
    *
    * This one cannot use `httpClient`: every `RequestBuilder` path ends in a `StrictResponse`, which
    * buffers the body and erases the chunk boundaries that are the whole claim. So it goes through
    * Akka HTTP directly, at the TestKit's own host and port, and counts the elements of the response
    * entity's byte stream. A buffered read would have "passed" against a non-streaming endpoint,
    * which would make the assertion worthless.
    */
  @Test
  def theAnswerArrivesAsMoreThanOneChunkOverTheWire(): Unit =
    val materializer = testKit.getMaterializer()
    val request = HttpRequest
      .POST(s"http://${testKit.getHost()}:${testKit.getPort()}/stream-chat/c-chunks")
      .withEntity(ContentTypes.APPLICATION_JSON, """{"message":"why does work survive a restart?"}""")

    val response = Http
      .get(materializer.system)
      .singleRequest(request)
      .toCompletableFuture
      .get(30, TimeUnit.SECONDS)

    assertThat(response.status()).isEqualTo(StatusCodes.OK)

    val chunks = response
      .entity()
      .getDataBytes()
      .runWith(Sink.seq[ByteString], materializer)
      .toCompletableFuture
      .get(30, TimeUnit.SECONDS)
      .asScala
      .toList

    // Grouped, so far fewer than the 57 model tokens (FR-007) — but more than one, or nothing is
    // being streamed at all.
    // >1 is what makes this a stream. AssertJ's `describedAs` cannot be chained from Scala here —
    // it returns `SELF`, which scalac will not resolve further — and the default message already
    // reads "expected: > 1 but was: 1", which says the same thing.
    assertThat(chunks.size).isGreaterThan(1)

    // And grouping must not corrupt the answer: the chunks still reassemble exactly.
    assertThat(chunks.map(_.utf8String).mkString).isEqualTo(Answer)

  /** T013 / SC-005 — the half **this** surface can prove.
    *
    * Two streamed turns on one `sessionId` each deliver their own answer, and a third turn on a
    * different id is an independent conversation. The scripted replies are keyed on the question, so a
    * turn that reached the agent with the wrong message would fail rather than silently pass.
    *
    * What a mocked model **cannot** show is *recall* — it sees only the current turn (capability 4
    * research R6, re-confirmed by capability 6), so no assertion here claims the second answer used
    * the first. And the substance of retention and isolation — what is actually in session memory —
    * is asserted in `StreamProbeIntegrationTest` (Java), because reading `SessionMemoryEntity` needs
    * a Java method reference (capability 4 §6). Keeping it there means the Java quarantine does not
    * grow to a second class.
    */
  @Test
  def repeatedTurnsOnOneSessionEachStreamTheirOwnAnswer(): Unit =
    val introduction = "Nice to meet you, Ada."
    val recollection = "Your name is Ada."

    model.reset()
    model.whenMessage((m: String) => m.contains("my name is Ada")).reply(introduction)
    model.whenMessage((m: String) => m.contains("what is my name?")).reply(recollection)

    val first = post("c-multi", "my name is Ada")
    val second = post("c-multi", "what is my name?")
    val separate = post("c-other", "what is my name?")

    assertThat(first.status()).isEqualTo(StatusCodes.OK)
    assertThat(first.body().utf8String).isEqualTo(introduction)

    // The same conversation continues to work, and turn 2 got its own answer.
    assertThat(second.status()).isEqualTo(StatusCodes.OK)
    assertThat(second.body().utf8String).isEqualTo(recollection)

    // A different id is a separate conversation; it streams independently.
    assertThat(separate.status()).isEqualTo(StatusCodes.OK)
    assertThat(separate.body().utf8String).isEqualTo(recollection)

  /** An absent `message` is the same rejection rather than a 500 — the `Option` boundary from
    * feature 003, and the reason the domain takes an `Option` at all. */
  @Test
  def anAbsentMessageIsRejectedTheSameWay(): Unit =
    val response = httpClient
      .POST("/stream-chat/c-absent")
      .withRequestBody("{}")
      .invoke()

    assertThat(response.status()).isEqualTo(StatusCodes.BAD_REQUEST)

object StreamingChatEndpointIntegrationTest:
  /** The endpoint's wire shape, restated on the test side so the test does not depend on the Java
    * endpoint's nested record (and so a change to it is visible here as a compile error). */
  final case class Body(message: String)
