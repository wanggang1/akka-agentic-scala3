package com.gwgs.akkaagentic.a2a.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The immutable to-do list that is a personal assistant's persisted state (capability 6).
 *
 * <p>This is the {@code TodoEntity} KeyValueEntity state, keyed by username. It owns the TODO
 * business logic (add / delete / complete / id assignment) so the entity's command handlers stay
 * thin — per AGENTS.md, domain logic lives in the domain object, not the entity.
 *
 * <p>Immutable: every mutator returns a new {@code TodoList} with a defensively copied list, and the
 * backing list is copied on the way in. Java-shaped for the same reason as {@link Todo}.
 *
 * <p>Ids are <strong>monotonic and never reused</strong> within a list: {@code nextId} is a
 * high-water mark that only ever grows, so deleting the highest item does not let a later
 * {@code add} reclaim its id (which would confuse an ongoing conversation that already referred to
 * "item N"). This is why the next id is a persisted field rather than derived from {@code max(id)+1}.
 */
public record TodoList(List<Todo> todos, int nextId) {

  public TodoList {
    todos = List.copyOf(todos); // defensive, unmodifiable copy on construction
  }

  /** An assistant that has never recorded a to-do; the first assigned id is 1. */
  public static TodoList empty() {
    return new TodoList(List.of(), 1);
  }

  /** A new list with an incomplete to-do appended, assigned the current {@link #nextId()}. */
  public TodoList add(String description) {
    var updated = new ArrayList<>(todos);
    updated.add(new Todo(nextId, description, false));
    return new TodoList(updated, nextId + 1); // high-water mark advances, never rewinds
  }

  /** The to-do with the given id, if present. */
  public Optional<Todo> find(int id) {
    return todos.stream().filter(t -> t.id() == id).findFirst();
  }

  /** A new list with the given id removed; unchanged (a copy) if no such id exists. {@code nextId} is preserved. */
  public TodoList delete(int id) {
    var updated = new ArrayList<Todo>();
    for (var t : todos) {
      if (t.id() != id) updated.add(t);
    }
    return new TodoList(updated, nextId);
  }

  /** A new list with the given id's completed flag set; unchanged (a copy) if no such id exists. {@code nextId} is preserved. */
  public TodoList setCompleted(int id, boolean completed) {
    var updated = new ArrayList<Todo>(todos.size());
    for (var t : todos) {
      updated.add(t.id() == id ? new Todo(t.id(), t.description(), completed) : t);
    }
    return new TodoList(updated, nextId);
  }
}
