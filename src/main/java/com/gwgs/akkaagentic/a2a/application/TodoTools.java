package com.gwgs.akkaagentic.a2a.application;

import akka.javasdk.annotations.Description;
import akka.javasdk.annotations.FunctionTool;
import akka.javasdk.client.ComponentClient;
import com.gwgs.akkaagentic.a2a.domain.TodoList;
import java.util.stream.Collectors;

/**
 * The to-do tools the personal assistant exposes to the model (capability 6).
 *
 * <p>This is an <em>external tool object</em>, not a component — the Scala {@code PersonalAssistantAgent}
 * registers it with {@code effects().tools(new TodoTools(componentClient, username))}. It is Java because
 * it calls {@link TodoEntity} by <strong>method reference</strong> ({@code .method(TodoEntity::add)}) —
 * the entity client has no {@code dynamicCall}, so this is unreachable from Scala (specs/008 research R2).
 * Keeping the calls here lets the agent stay Scala: this Java tool object is the "seam".
 *
 * <p>Constructed per request with the <em>caller's</em> {@code username}, so every operation targets that
 * user's list — which is what makes a delegated request act on the target user's to-dos.
 */
public class TodoTools {

  private final ComponentClient componentClient;
  private final String username; // the TodoEntity id = whose list these tools act on

  public TodoTools(ComponentClient componentClient, String username) {
    this.componentClient = componentClient;
    this.username = username;
  }

  @FunctionTool(
      description =
          "List all of the current user's to-dos, each with its id, description, and completed status.")
  public String listTodos() {
    TodoList list = componentClient.forKeyValueEntity(username).method(TodoEntity::list).invoke();
    if (list.todos().isEmpty()) return "The to-do list is empty.";
    return list.todos().stream()
        .map(t -> "%d. %s (%s)".formatted(t.id(), t.description(), t.completed() ? "done" : "open"))
        .collect(Collectors.joining("\n"));
  }

  @FunctionTool(description = "Add a new to-do for the current user. Returns the new to-do's id.")
  public int addTodo(@Description("the to-do description") String description) {
    return componentClient.forKeyValueEntity(username).method(TodoEntity::add).invoke(description);
  }

  @FunctionTool(
      description = "Delete a to-do by id for the current user. Returns true if it existed and was deleted.")
  public boolean deleteTodo(@Description("the to-do id") int id) {
    return componentClient.forKeyValueEntity(username).method(TodoEntity::delete).invoke(id);
  }

  @FunctionTool(
      description =
          "Mark a to-do as completed or not completed for the current user. Returns true if the id existed.")
  public boolean setCompleted(
      @Description("the to-do id") int id,
      @Description("true to mark completed, false to reopen") boolean completed) {
    return componentClient
        .forKeyValueEntity(username)
        .method(TodoEntity::setCompleted)
        .invoke(new TodoEntity.SetCompletedCmd(id, completed));
  }
}
