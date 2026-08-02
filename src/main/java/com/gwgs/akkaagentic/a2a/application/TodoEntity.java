package com.gwgs.akkaagentic.a2a.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import akka.javasdk.keyvalueentity.KeyValueEntityContext;
import com.gwgs.akkaagentic.a2a.domain.Todo;
import com.gwgs.akkaagentic.a2a.domain.TodoList;

/**
 * A personal assistant's persisted to-do list (capability 6), keyed by username (the entity id).
 *
 * <p>Java, not Scala, for two reasons (specs/008 research R2 + the language-of-consumer rule): its
 * client is <em>method-reference-only</em> (no {@code dynamicCall}), so it can only be called from Java
 * — hence the Java {@link TodoTools} tool object that wraps it — and its {@link TodoList} state crosses
 * the SDK's internal Jackson mapper, so it must be Java-shaped anyway. The Scala agent never touches
 * this entity directly; it reaches it only through {@code TodoTools}.
 *
 * <p>Thin command handlers: all list logic lives in {@link TodoList} (AGENTS.md — domain logic in the
 * domain object). The only way to persist a change is {@code effects().updateState(...)}.
 */
@Component(id = "todo-entity")
public class TodoEntity extends KeyValueEntity<TodoList> {

  /** Parameters for {@link #setCompleted} — wrapped because a command handler takes a single argument. */
  public record SetCompletedCmd(int id, boolean completed) {}

  private final String username;

  public TodoEntity(KeyValueEntityContext context) {
    this.username = context.entityId();
  }

  @Override
  public TodoList emptyState() {
    return TodoList.empty();
  }

  /** Current list (read-only). */
  public ReadOnlyEffect<TodoList> list() {
    return effects().reply(currentState());
  }

  /** Append an incomplete to-do; reply with its newly assigned (monotonic) id. */
  public Effect<Integer> add(String description) {
    int newId = currentState().nextId();
    return effects().updateState(currentState().add(description)).thenReply(newId);
  }

  /** Delete a to-do by id; reply whether it existed (and was removed). */
  public Effect<Boolean> delete(int id) {
    boolean found = currentState().find(id).isPresent();
    return effects().updateState(currentState().delete(id)).thenReply(found);
  }

  /** Set a to-do's completed flag; reply whether the id existed. */
  public Effect<Boolean> setCompleted(SetCompletedCmd cmd) {
    boolean found = currentState().find(cmd.id()).isPresent();
    return effects()
        .updateState(currentState().setCompleted(cmd.id(), cmd.completed()))
        .thenReply(found);
  }
}
