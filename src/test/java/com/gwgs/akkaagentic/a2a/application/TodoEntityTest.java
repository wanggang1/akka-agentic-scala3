package com.gwgs.akkaagentic.a2a.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import com.gwgs.akkaagentic.a2a.domain.TodoList;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TodoEntity} using {@link KeyValueEntityTestKit} (no runtime, no model). */
class TodoEntityTest {

  private KeyValueEntityTestKit<TodoList, TodoEntity> newKit() {
    return KeyValueEntityTestKit.of("alice", TodoEntity::new);
  }

  @Test
  void addReturnsIncreasingIdsAndPersists() {
    var kit = newKit();

    assertThat(kit.method(TodoEntity::add).invoke("buy milk").getReply()).isEqualTo(1);
    assertThat(kit.method(TodoEntity::add).invoke("call dentist").getReply()).isEqualTo(2);

    assertThat(kit.getState().todos())
        .extracting("id", "description", "completed")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1, "buy milk", false),
            org.assertj.core.groups.Tuple.tuple(2, "call dentist", false));
  }

  @Test
  void deleteReportsFoundThenNotFound() {
    var kit = newKit();
    kit.method(TodoEntity::add).invoke("buy milk"); // id 1

    assertThat(kit.method(TodoEntity::delete).invoke(1).getReply()).isTrue();
    assertThat(kit.method(TodoEntity::delete).invoke(1).getReply()).isFalse();
    assertThat(kit.getState().todos()).isEmpty();
  }

  @Test
  void setCompletedFlipsFlagAndReportsFound() {
    var kit = newKit();
    kit.method(TodoEntity::add).invoke("buy milk"); // id 1

    var found =
        kit.method(TodoEntity::setCompleted)
            .invoke(new TodoEntity.SetCompletedCmd(1, true))
            .getReply();
    assertThat(found).isTrue();
    assertThat(kit.getState().find(1)).hasValueSatisfying(t -> assertThat(t.completed()).isTrue());

    var missing =
        kit.method(TodoEntity::setCompleted)
            .invoke(new TodoEntity.SetCompletedCmd(99, true))
            .getReply();
    assertThat(missing).isFalse();
  }

  @Test
  void listReflectsCurrentState() {
    var kit = newKit();
    kit.method(TodoEntity::add).invoke("a");
    kit.method(TodoEntity::add).invoke("b");

    var list = kit.method(TodoEntity::list).invoke().getReply();
    assertThat(list.todos()).hasSize(2);
  }

  @Test
  void idsAreNotReusedAfterDeleteAtEntityLevel() {
    var kit = newKit();
    kit.method(TodoEntity::add).invoke("a"); // 1
    kit.method(TodoEntity::add).invoke("b"); // 2
    assertThat(kit.method(TodoEntity::delete).invoke(2).getReply()).isTrue();

    // Next add must NOT reclaim id 2 (monotonic high-water mark).
    assertThat(kit.method(TodoEntity::add).invoke("c").getReply()).isEqualTo(3);
  }
}
