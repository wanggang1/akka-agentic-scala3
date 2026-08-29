package com.gwgs.akkaagentic.todos.api

import java.time.Duration

import akka.http.javadsl.model.StatusCodes
import akka.javasdk.testkit.EventingTestKit.IncomingMessages
import akka.javasdk.testkit.{TestKit, TestKitSupport}
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.Test

import com.gwgs.akkaagentic.a2a.application.TodoEntity
import com.gwgs.akkaagentic.a2a.domain.TodoList

/** Drives [[TodoSummaryEndpoint]] over HTTP — **with no model at all**, mocked or live.
  *
  * This test is the concrete proof of how narrow the method-ref wall is here: the endpoint under test
  * had to be written in **Java** because `ViewClient` is method-reference-only (research R1), yet the
  * test exercising it is **Scala**. It never touches `ViewClient`: it seeds the projection through the
  * `Class`-keyed `withKeyValueEntityIncomingMessages` publisher and reads back over `httpClient`,
  * neither of which involves a method reference. Contrast cap-4's `SessionMemoryIntegrationTest`,
  * where the *test itself* was forced into Java because it had to hold the method ref.
  *
  * Views are eventually consistent, so every read is wrapped in `Awaitility` rather than asserted
  * once (FR-009).
  */
class TodoSummaryEndpointIntegrationTest extends TestKitSupport:

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT.withKeyValueEntityIncomingMessages(classOf[TodoEntity])

  private def todoStates: IncomingMessages =
    testKit.getKeyValueEntityIncomingMessages(classOf[TodoEntity])

  private def withOpenWork() =
    httpClient
      .GET("/todo-summaries/with-open-work")
      .responseBodyAs(classOf[TodoSummaryEndpoint.TodoSummariesResponse])
      .invoke()

  private def getByUser(username: String) =
    httpClient
      .GET(s"/todo-summaries/by-user/$username")
      .responseBodyAs(classOf[TodoSummaryEndpoint.TodoSummaryResponse])
      .invoke()

  /** SC-001: a projected user is served over HTTP with the right counts. */
  @Test
  def knownUsernameReturns200WithTheCounts(): Unit =
    val list = TodoList.empty.add("buy milk").add("call dentist").add("pay rent").setCompleted(2, true)
    todoStates.publish(list, "http-alice")

    Awaitility
      .await()
      .ignoreExceptions() // a 404 before the projection catches up raises, and is retried
      .atMost(Duration.ofSeconds(10))
      .untilAsserted: () =>
        val reply = getByUser("http-alice")
        assertThat(reply.status()).isEqualTo(StatusCodes.OK)
        assertThat(reply.body().username).isEqualTo("http-alice")
        assertThat(reply.body().total).isEqualTo(3)
        assertThat(reply.body().open).isEqualTo(2)
        assertThat(reply.body().completed).isEqualTo(1)

  /** SC-002 / FR-004: an unknown username is a clean 404 — never a zero-filled 200.
    *
    * `responseBodyAs` is deliberately OMITTED here: it throws on a non-2xx status (established project
    * finding), so a failure-status assertion must read `status()` off the raw response.
    */
  @Test
  def unknownUsernameReturns404(): Unit =
    // Seed and await an unrelated user first, so this asserts genuine absence rather than lag.
    todoStates.publish(TodoList.empty.add("something"), "http-bob")
    Awaitility
      .await()
      .ignoreExceptions()
      .atMost(Duration.ofSeconds(10))
      .untilAsserted: () =>
        assertThat(getByUser("http-bob").status()).isEqualTo(StatusCodes.OK)

    val reply = httpClient.GET("/todo-summaries/by-user/http-nobody").invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.NOT_FOUND)

  /** FR-003: an assistant whose items were all deleted is 200 with zeros — distinct from the 404
    * above. "Nothing to do" and "no such assistant" must not collapse into one answer. */
  @Test
  def emptiedListReturns200WithZeroCountsNot404(): Unit =
    val carol = TodoList.empty.add("file taxes")
    todoStates.publish(carol, "http-carol")
    todoStates.publish(carol.delete(1), "http-carol")

    Awaitility
      .await()
      .ignoreExceptions()
      .atMost(Duration.ofSeconds(10))
      .untilAsserted: () =>
        val reply = getByUser("http-carol")
        assertThat(reply.status()).isEqualTo(StatusCodes.OK)
        assertThat(reply.body().total).isEqualTo(0)
        assertThat(reply.body().open).isEqualTo(0)
        assertThat(reply.body().completed).isEqualTo(0)

  /** SC-003 / FR-005: the cross-user query is served over HTTP with exactly the users holding open
    * work. Compared order-insensitively — the view declares no `ORDER BY`. */
  @Test
  def withOpenWorkReturns200WithExactlyTheUsersHoldingOpenItems(): Unit =
    val busy = TodoList.empty.add("ship it").add("review PR")
    val partly = TodoList.empty.add("write spec").add("file taxes").setCompleted(1, true)
    val done = TodoList.empty.add("renew passport").setCompleted(1, true)

    todoStates.publish(busy, "http-busy")
    todoStates.publish(partly, "http-partly")
    todoStates.publish(done, "http-done")

    Awaitility
      .await()
      .ignoreExceptions()
      .atMost(Duration.ofSeconds(10))
      .untilAsserted: () =>
        val reply = withOpenWork()
        assertThat(reply.status()).isEqualTo(StatusCodes.OK)
        val names = reply.body().summaries.stream.map(_.username).toList
        assertThat(names).contains("http-busy", "http-partly")
        assertThat(names).doesNotContain("http-done")

        // the counts survive the mapping to the API record, not just the usernames
        val busyRow = reply.body().summaries.stream.filter(_.username == "http-busy").findFirst.get
        assertThat(busyRow.total).isEqualTo(2)
        assertThat(busyRow.open).isEqualTo(2)
        assertThat(busyRow.completed).isEqualTo(0)

  /** SC-005 / FR-007: a whitespace-only username is rejected `400` before the view is queried, and is
    * a *distinct* answer from the `404` of an unknown-but-well-formed username above. Three outcomes,
    * three meanings: 400 malformed, 404 no such user, 200 (possibly all-zero) a real row.
    *
    * `responseBodyAs` is omitted deliberately — it throws on a non-2xx status, so a failure-status
    * assertion must read `status()` off the raw response (established project finding).
    */
  @Test
  def blankUsernameReturns400NotFoundOr200(): Unit =
    val reply = httpClient.GET("/todo-summaries/by-user/%20%20").invoke()
    assertThat(reply.status()).isEqualTo(StatusCodes.BAD_REQUEST)
    assertThat(reply.status()).isNotEqualTo(StatusCodes.NOT_FOUND)
