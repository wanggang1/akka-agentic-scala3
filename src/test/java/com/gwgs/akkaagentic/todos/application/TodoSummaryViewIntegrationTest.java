package com.gwgs.akkaagentic.todos.application;

import akka.javasdk.testkit.EventingTestKit.IncomingMessages;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.gwgs.akkaagentic.a2a.application.TodoEntity;
import com.gwgs.akkaagentic.a2a.domain.TodoList;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline proof that the {@code todo_summaries} projection is wired and correct — the capability's
 * first test that touches the Akka runtime, and (like every cap-11 test) it runs with
 * <strong>no model at all</strong>: no {@code TestModelProvider}, mocked or live.
 *
 * <p>State changes are injected straight into the projection with the eventing testkit
 * ({@code withKeyValueEntityIncomingMessages} + {@code publish(state, subject)}), so no entity command,
 * no agent and no LLM is involved (research R5). The published {@code subject} is the source entity id,
 * which for {@code TodoEntity} is the username — and therefore the view row key.
 *
 * <p><strong>Why this test is Java, in an otherwise-Scala capability:</strong> it queries the View
 * through {@code componentClient.forView().method(TodoSummaryView::getByUsername)}, and {@code ViewClient}
 * offers <em>only</em> method-reference overloads — no {@code dynamicCall} escape hatch — so a Scala
 * lambda (a synthetic {@code $anonfun}) can never resolve (research R1). Same forced-Java-test precedent
 * as cap-4's {@code SessionMemoryIntegrationTest}. Note what is <em>not</em> forced: the sibling
 * endpoint test stays Scala, because it drives the same projection through {@code httpClient} and the
 * {@code Class}-keyed testkit publisher — neither of which involves a method reference.
 *
 * <p>Every assertion is wrapped in {@code Awaitility}, never a bare assert: a View is
 * <strong>eventually consistent</strong>, so a query issued immediately after a publish may legitimately
 * see the old row or no row (FR-009).
 */
public class TodoSummaryViewIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withKeyValueEntityIncomingMessages(TodoEntity.class);
  }

  private IncomingMessages todoStates() {
    return testKit.getKeyValueEntityIncomingMessages(TodoEntity.class);
  }

  /** Query the view by username. Java-only: this method reference is the wall (research R1). */
  private Optional<TodoSummaryEntry> lookup(String username) {
    return componentClient.forView().method(TodoSummaryView::getByUsername).invoke(username);
  }

  /** Every user with at least one open item. Java-only for the same reason as {@link #lookup}. */
  private List<TodoSummaryEntry> withOpenWork() {
    return componentClient.forView().method(TodoSummaryView::withOpenWork).invoke().entries();
  }

  /** The usernames currently reported as having open work. */
  private List<String> openWorkUsernames() {
    return withOpenWork().stream().map(TodoSummaryEntry::username).toList();
  }

  /** Await until the row for {@code username} exists and matches the expected counts. */
  private void awaitCounts(String username, int total, int open, int completed) {
    Awaitility.await()
        .ignoreExceptions()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(lookup(username))
                    .contains(new TodoSummaryEntry(username, total, open, completed)));
  }

  /** SC-001: a published list is projected into a row with the right counts. */
  @Test
  public void projectsAPublishedListIntoCorrectCounts() {
    var alice = TodoList.empty().add("buy milk").add("call dentist").add("pay rent");
    todoStates().publish(alice.setCompleted(2, true), "view-alice");

    awaitCounts("view-alice", 3, 2, 1);
  }

  /**
   * SC-002 / FR-012: a second update to the same username <em>replaces</em> the row rather than
   * accumulating onto it. This is the redelivery-safety property in miniature: the row is a pure
   * function of the latest state, never a running total.
   */
  @Test
  public void aLaterUpdateReplacesTheRowRatherThanAccumulating() {
    var first = TodoList.empty().add("a").add("b");
    todoStates().publish(first, "view-bob");
    awaitCounts("view-bob", 2, 2, 0);

    // Same list with one item completed and one added: totals must be 3/2/1, not 5 or 2+3.
    todoStates().publish(first.setCompleted(1, true).add("c"), "view-bob");
    awaitCounts("view-bob", 3, 2, 1);

    // And publishing the SAME state again must not change anything.
    todoStates().publish(first.setCompleted(1, true).add("c"), "view-bob");
    awaitCounts("view-bob", 3, 2, 1);
  }

  /**
   * FR-003: a user whose items were all deleted keeps a row with all-zero counts — "this assistant has
   * no to-dos", which is a different answer from "no such assistant".
   */
  @Test
  public void anEmptiedListYieldsAnAllZeroRowNotAMissingOne() {
    var carol = TodoList.empty().add("file taxes");
    todoStates().publish(carol, "view-carol");
    awaitCounts("view-carol", 1, 1, 0);

    todoStates().publish(carol.delete(1), "view-carol");
    awaitCounts("view-carol", 0, 0, 0);
  }

  /**
   * FR-004 (and the research R6 decision point): a username the projection has never seen returns
   * <em>no row</em>, cleanly — not an error, and not a zero-filled row.
   */
  @Test
  public void anUnknownUsernameYieldsNotFound() {
    // Publish an unrelated user first, and wait for it, so we know the projection is live and this
    // assertion is about absence rather than about not having caught up yet.
    todoStates().publish(TodoList.empty().add("something"), "view-dave");
    awaitCounts("view-dave", 1, 1, 0);

    assertThat(lookup("view-nobody-ever-heard-of")).isEmpty();
  }

  /**
   * SC-003 / FR-005: the cross-user query returns exactly the users holding open work — the question
   * the write side cannot answer at all, since a KeyValueEntity is reachable only by its own id.
   *
   * <p>Asserted order-insensitively: the query declares no {@code ORDER BY} (contracts/http-api.md).
   */
  @Test
  public void returnsExactlyTheUsersWithOpenWork() {
    var busy = TodoList.empty().add("ship the thing").add("review the PR"); // 2 open
    var partly = TodoList.empty().add("write spec").add("file taxes").setCompleted(1, true); // 1 open
    var done = TodoList.empty().add("renew passport").setCompleted(1, true); // 0 open
    var never = TodoList.empty(); // 0 items at all

    todoStates().publish(busy, "open-busy");
    todoStates().publish(partly, "open-partly");
    todoStates().publish(done, "open-done");
    todoStates().publish(never, "open-never");

    Awaitility.await()
        .ignoreExceptions()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(openWorkUsernames())
                    .contains("open-busy", "open-partly")
                    .doesNotContain("open-done", "open-never"));
  }

  /**
   * FR-006: a user whose items are all completed is excluded from the cross-user query while still
   * being findable by the keyed lookup. "Has no open work" must not be confused with "does not exist"
   * — the two queries disagree about that user on purpose.
   */
  @Test
  public void anAllCompletedUserIsExcludedFromOpenWorkButStillFoundByKey() {
    var quiet = TodoList.empty().add("a").add("b").setCompleted(1, true).setCompleted(2, true);
    todoStates().publish(quiet, "open-quiet");

    awaitCounts("open-quiet", 2, 0, 2); // the row exists, and the keyed lookup finds it
    assertThat(openWorkUsernames()).doesNotContain("open-quiet");
  }
}
