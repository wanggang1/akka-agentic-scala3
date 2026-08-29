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

/** SC-004 / FR-006 over HTTP: with nobody holding open work, `GET /todo-summaries/with-open-work`
  * answers `200` with `{"summaries":[]}` — **never** `404`. An empty result is a successful answer to
  * a well-formed question; only the keyed lookup has a not-found.
  *
  * In its own class for the same reason as
  * [[com.gwgs.akkaagentic.todos.application.TodoSummaryEmptyWorldIntegrationTest]]: `TestKitSupport`
  * shares one runtime across a class's methods, and the sibling tests deliberately leave users with
  * open work in the projection. As there, the emptiness is *earned* — a user is driven into the result
  * and then completed out of it — so an empty list cannot be confused with a projection lagging.
  */
class TodoSummaryEmptyWorldEndpointIntegrationTest extends TestKitSupport:

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT.withKeyValueEntityIncomingMessages(classOf[TodoEntity])

  private def todoStates: IncomingMessages =
    testKit.getKeyValueEntityIncomingMessages(classOf[TodoEntity])

  private def withOpenWork() =
    httpClient
      .GET("/todo-summaries/with-open-work")
      .responseBodyAs(classOf[TodoSummaryEndpoint.TodoSummariesResponse])
      .invoke()

  @Test
  def withNoOpenWorkAnywhereReturns200WithAnEmptyArray(): Unit =
    val list = TodoList.empty.add("one last thing")
    todoStates.publish(list, "http-empty-world-user")

    Awaitility
      .await()
      .ignoreExceptions()
      .atMost(Duration.ofSeconds(10))
      .untilAsserted: () =>
        assertThat(withOpenWork().body().summaries).hasSize(1)

    todoStates.publish(list.setCompleted(1, true), "http-empty-world-user")

    Awaitility
      .await()
      .ignoreExceptions()
      .atMost(Duration.ofSeconds(10))
      .untilAsserted: () =>
        val reply = withOpenWork()
        assertThat(reply.status()).isEqualTo(StatusCodes.OK) // success, not 404
        assertThat(reply.body().summaries).isEmpty()
