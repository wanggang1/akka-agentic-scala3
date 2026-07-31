package com.gwgs.akkaagentic.a2a.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for the pure {@link TodoList} domain object (no Akka, deterministic). */
class TodoListTest {

  @Test
  void emptyHasNoTodosAndNextIdIsOne() {
    var list = TodoList.empty();
    assertThat(list.todos()).isEmpty();
    assertThat(list.nextId()).isEqualTo(1);
  }

  @Test
  void addAssignsNextIdAndAppendsIncompleteItem() {
    var list = TodoList.empty().add("buy milk");

    assertThat(list.todos()).hasSize(1);
    assertThat(list.find(1)).contains(new Todo(1, "buy milk", false));
    assertThat(list.nextId()).isEqualTo(2);
  }

  @Test
  void addsIncrementIdsMonotonically() {
    var list = TodoList.empty().add("a").add("b").add("c");

    assertThat(list.todos())
        .extracting(Todo::id, Todo::description)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1, "a"),
            org.assertj.core.groups.Tuple.tuple(2, "b"),
            org.assertj.core.groups.Tuple.tuple(3, "c"));
  }

  @Test
  void idsAreNotReusedAfterDelete() {
    var list = TodoList.empty().add("a").add("b").delete(2);

    // Deleting id 2 must not let the next add reclaim it — nextId is max(id)+1.
    assertThat(list.nextId()).isEqualTo(3);
    var grown = list.add("c");
    assertThat(grown.find(3)).contains(new Todo(3, "c", false));
    assertThat(grown.find(2)).isEmpty();
  }

  @Test
  void deleteRemovesTheMatchingIdOnly() {
    var list = TodoList.empty().add("a").add("b").delete(1);

    assertThat(list.find(1)).isEmpty();
    assertThat(list.find(2)).contains(new Todo(2, "b", false));
  }

  @Test
  void deleteUnknownIdIsANoOp() {
    var list = TodoList.empty().add("a");
    var afterDelete = list.delete(99);

    assertThat(afterDelete.todos()).isEqualTo(list.todos());
  }

  @Test
  void setCompletedFlipsTheFlagForTheMatchingId() {
    var list = TodoList.empty().add("a").add("b").setCompleted(1, true);

    assertThat(list.find(1)).contains(new Todo(1, "a", true));
    assertThat(list.find(2)).contains(new Todo(2, "b", false));

    var reopened = list.setCompleted(1, false);
    assertThat(reopened.find(1)).contains(new Todo(1, "a", false));
  }

  @Test
  void setCompletedUnknownIdIsANoOp() {
    var list = TodoList.empty().add("a");
    var after = list.setCompleted(99, true);

    assertThat(after.todos()).isEqualTo(list.todos());
  }

  @Test
  void mutatorsDoNotMutateTheOriginal() {
    var original = TodoList.empty().add("a");

    original.add("b");
    original.delete(1);
    original.setCompleted(1, true);

    // original is unchanged by any of the above (immutability / copy-on-write).
    assertThat(original.todos()).containsExactly(new Todo(1, "a", false));
  }
}
