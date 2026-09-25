package com.gwgs.akkaagentic.feed.api

import java.util.concurrent.TimeUnit

import akka.javasdk.testkit.{TestKit, TestKitSupport}
import com.gwgs.akkaagentic.a2a.application.TodoEntity
import com.gwgs.akkaagentic.a2a.domain.{Todo, TodoList}
import com.gwgs.akkaagentic.feed.api.TodoActivityEndpoint.FeedView
import com.gwgs.akkaagentic.feed.application.ActivityStore
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility
import org.junit.jupiter.api.{BeforeEach, Test}

/** T013 / T014 — User Story 1 over real HTTP: a change at the source appears in the feed, unasked.
  *
  * **The mocked incoming channel is used deliberately, and only for what it models faithfully.** Delivery,
  * the input type, the delete handler and the metadata are exactly as the real projection delivers them
  * (specs/018 research Q-F). What it does *not* model is failure: it drops a failing message instead of
  * redelivering it, and loses the ones behind it — so User Story 2 is tested on the real path instead.
  *
  * No model anywhere: the feed reacts to state, and needs no language model to do it.
  *
  * **What idempotence here does and does not promise.** A duplicate delivery is an *identical* state, and
  * an identical state differs in nothing, so it records nothing — that is SC-003, asserted below. What
  * cannot be promised from this source is exactly-once *display*: a key-value state carries no version, so
  * if the platform ever replayed an **older committed** state after a newer one, the comparison would
  * report the difference in reverse (a reversal pair) rather than recognising it as history. FR-004 still
  * holds either way — entries stay in order and the last one matches the final state — and the reversal
  * case was not observed in any measurement; it is recorded as unverified rather than claimed impossible
  * (specs/018 research, "what remains unverified").
  */
class TodoActivityIntegrationTest extends TestKitSupport:

  override protected def testKitSettings(): TestKit.Settings =
    TestKit.Settings.DEFAULT
      .withAdditionalConfig("akka.javasdk.agent.googleai-gemini.api-key = n/a")
      .withKeyValueEntityIncomingMessages(classOf[TodoEntity])

  private def incoming = testKit.getKeyValueEntityIncomingMessages(classOf[TodoEntity])

  @BeforeEach
  def reset(): Unit = ActivityStore.clear()

  /** Capability 6's state, built the way capability 6 builds it. */
  private def list(items: (String, Boolean)*): TodoList =
    items.foldLeft(TodoList.empty())((l, item) => l.add(item._1)) match
      case built =>
        items.zipWithIndex.foldLeft(built) { case (l, ((_, completed), i)) =>
          if completed then l.setCompleted(i + 1, true) else l
        }

  private def feedFor(username: String): FeedView =
    httpClient.GET(s"/todo-activity/$username").responseBodyAs(classOf[FeedView]).invoke().body()

  private def kindsFor(username: String): String =
    feedFor(username).entries.map(_.kind).mkString(",")

  private def awaitKinds(username: String, expected: String): Unit =
    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() => assertThat(kindsFor(username)).isEqualTo(expected))

  // --- T013 ---------------------------------------------------------------------------------------

  @Test
  def aChangeAtTheSourceAppearsInTheFeedWithNobodyAsking(): Unit =
    incoming.publish(list(("buy milk", false)), "alice")
    // First sighting: a baseline, NOT a fabricated "added" for an item the feed never saw appear.
    awaitKinds("alice", "baseline")
    val entry = feedFor("alice").entries.head
    assertThat(entry.username).isEqualTo("alice")
    assertThat(entry.open.getOrElse(-1)).isEqualTo(1)
    assertThat(entry.itemId.isDefined).isFalse() // a baseline is about the list, not one item

  @Test
  def additionsCompletionsAndRemovalsAreEachRecognised(): Unit =
    incoming.publish(list(("buy milk", false)), "bob")
    awaitKinds("bob", "baseline")
    incoming.publish(list(("buy milk", false), ("call mum", false)), "bob")
    awaitKinds("bob", "baseline,added")
    incoming.publish(list(("buy milk", true), ("call mum", false)), "bob")
    awaitKinds("bob", "baseline,added,completed")
    incoming.publish(list(("buy milk", false), ("call mum", false)), "bob")
    awaitKinds("bob", "baseline,added,completed,reopened")
    incoming.publish(list(("buy milk", false)), "bob")
    awaitKinds("bob", "baseline,added,completed,reopened,removed")

    val entries = feedFor("bob").entries
    assertThat(entries.last.itemId.getOrElse(-1)).isEqualTo(2)
    assertThat(entries.last.description.getOrElse("")).isEqualTo("call mum")

  @Test
  def deletingTheWholeListIsRecordedAsSuch(): Unit =
    incoming.publish(list(("buy milk", false)), "carol")
    awaitKinds("carol", "baseline")
    incoming.publishDelete("carol")
    awaitKinds("carol", "baseline,list-deleted")

  @Test
  def readingTheWholeFeedIsAlwaysA200WithItsWindow(): Unit =
    // Emptiness is asserted on a user nobody touched (below) rather than on the whole feed: another test
    // in this class may legitimately have recorded something, and a test that depends on execution order
    // would flake rather than fail honestly.
    val response = httpClient.GET("/todo-activity").responseBodyAs(classOf[FeedView]).invoke()
    assertThat(response.status().intValue()).isEqualTo(200)
    assertThat(response.body().since).isNotBlank()

  @Test
  def anUnknownUserHasNoEntriesRatherThanA404(): Unit =
    // Also the "empty is a success" case: 200 with no entries, never a 404. The feed knows who has done
    // something since `since`; silence is not "no such user".
    val response = httpClient.GET("/todo-activity/nobody").responseBodyAs(classOf[FeedView]).invoke()
    assertThat(response.status().intValue()).isEqualTo(200)
    assertThat(response.body().entries.isEmpty).isTrue()

  @Test
  def everyResponseSaysWhichWindowItCovers(): Unit =
    // `since` is the honest half of an in-process feed: the consumer resumes after a restart and resends
    // nothing (research Q-E), so a reader must be able to see the window rather than assume completeness.
    assertThat(feedFor("alice").since).isNotBlank()

  // --- T023: SC-003 — a repeated delivery is not a repeated event ---------------------------------

  @Test
  def theSameStateDeliveredTwiceIsRecordedOnce(): Unit =
    val state = list(("buy milk", false))
    incoming.publish(state, "erin")
    awaitKinds("erin", "baseline")
    incoming.publish(state, "erin")
    // Nothing more can arrive for an identical state; give the second delivery time to be wrong.
    Thread.sleep(1500)
    assertThat(kindsFor("erin")).isEqualTo("baseline")

  @Test
  def aRepeatOfTheLatestStateAfterARealChangeRecordsNothingFurther(): Unit =
    incoming.publish(list(("a", false)), "frank")
    awaitKinds("frank", "baseline")
    incoming.publish(list(("a", false), ("b", false)), "frank")
    awaitKinds("frank", "baseline,added")
    incoming.publish(list(("a", false), ("b", false)), "frank") // the same state again
    Thread.sleep(1500)
    assertThat(kindsFor("frank")).isEqualTo("baseline,added")

  // --- T014: SC-002 on a key-value source ----------------------------------------------------------

  @Test
  def acrossFiveChangesTheEntriesAreNeverOutOfOrderAndTheLastMatchesTheFinalState(): Unit =
    // NOT "one entry per change": a key-value source delivers the latest state and may fold changes
    // together, so the feed promises order and the final state, and says so (spec FR-004).
    incoming.publish(list(("a", false)), "dave")
    awaitKinds("dave", "baseline")
    incoming.publish(list(("a", false), ("b", false)), "dave")
    incoming.publish(list(("a", false), ("b", false), ("c", false)), "dave")
    incoming.publish(list(("a", true), ("b", false), ("c", false)), "dave")
    incoming.publish(list(("a", true), ("b", true), ("c", false)), "dave")

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted { () =>
      val entries = feedFor("dave").entries
      assertThat(entries.size).isGreaterThanOrEqualTo(3)
      // sequences ascending — never out of order
      assertThat(entries.map(_.sequence)).isEqualTo(entries.map(_.sequence).sorted)
      // and the feed's view of the list matches the final state: a and b completed, c still open
      assertThat(entries.count(_.kind == "completed")).isGreaterThanOrEqualTo(1)
      assertThat(ActivityStore.feed.lastState("dave").completedCount).isEqualTo(2)
      assertThat(ActivityStore.feed.lastState("dave").open).isEqualTo(1)
    }
