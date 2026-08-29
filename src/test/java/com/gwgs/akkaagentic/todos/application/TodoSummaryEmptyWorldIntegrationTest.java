package com.gwgs.akkaagentic.todos.application;

import akka.javasdk.testkit.EventingTestKit.IncomingMessages;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.gwgs.akkaagentic.a2a.application.TodoEntity;
import com.gwgs.akkaagentic.a2a.domain.TodoList;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-004 / FR-006: when <em>nobody</em> has open work, the cross-user query returns an empty list
 * <strong>successfully</strong> — not an error, and not a not-found.
 *
 * <p><strong>Why this is a separate class</strong> rather than another method on
 * {@link TodoSummaryViewIntegrationTest}: {@code TestKitSupport} starts one runtime per test class and
 * shares it across that class's methods, so the sibling tests' rows (which deliberately do have open
 * work) would still be in the projection. "No user has open work" is a statement about the whole
 * projection, so it needs a world of its own.
 *
 * <p>The test earns its emptiness rather than assuming it: it first drives a user <em>into</em> the
 * open-work result, then completes that user's items and waits for them to drop out. So the final
 * empty list is proven to mean "nobody qualifies" and not "the projection has not caught up yet".
 */
public class TodoSummaryEmptyWorldIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withKeyValueEntityIncomingMessages(TodoEntity.class);
  }

  private IncomingMessages todoStates() {
    return testKit.getKeyValueEntityIncomingMessages(TodoEntity.class);
  }

  private TodoSummaryEntries withOpenWork() {
    return componentClient.forView().method(TodoSummaryView::withOpenWork).invoke();
  }

  @Test
  public void withNoOpenWorkAnywhereTheQuerySucceedsWithAnEmptyList() {
    var list = TodoList.empty().add("one last thing");
    todoStates().publish(list, "empty-world-user");

    // First prove the user IS in the result...
    Awaitility.await()
        .ignoreExceptions()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(withOpenWork().entries()).hasSize(1));

    // ...then complete their only item and watch them drop out.
    todoStates().publish(list.setCompleted(1, true), "empty-world-user");

    Awaitility.await()
        .ignoreExceptions()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var result = withOpenWork();
              assertThat(result).isNotNull(); // success, not an error
              assertThat(result.entries()).isEmpty(); // and not a not-found either
            });
  }
}
