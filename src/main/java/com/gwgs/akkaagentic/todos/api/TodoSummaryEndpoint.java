package com.gwgs.akkaagentic.todos.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import com.gwgs.akkaagentic.todos.application.TodoSummaryEntry;
import com.gwgs.akkaagentic.todos.application.TodoSummaryView;
import java.util.List;

/**
 * Read-only HTTP surface over the {@code todo_summaries} view (capability 11).
 *
 * <p><strong>Read-only on purpose.</strong> Only {@code GET} lives here, and no write method may be
 * added: to-dos are still changed exclusively through capability 6's assistant
 * ({@code POST /request/{username}}). This endpoint is the query side of that split.
 *
 * <p><strong>Why this class is Java in an otherwise-Scala capability</strong> (feature 013 research
 * R1): querying a View goes through {@code componentClient.forView().method(View::query)}, and
 * {@code ViewClient} exposes <em>only</em> those method-reference overloads — keyed on a Java
 * {@code SerializedLambda}, with <strong>no {@code dynamicCall}</strong> escape hatch, unlike the agent
 * client. A Scala lambda compiles to a synthetic {@code $anonfun} and never resolves. Same wall as
 * cap-2's {@code WorkflowClient} (README §4) and cap-6's {@code KeyValueEntityClient} (README §8).
 *
 * <p>What is new in cap-11 is how <em>little</em> the wall takes: the View component itself stays
 * Scala, and only this caller is Java. The wall is a property of the <em>client</em>, and it travels
 * no further than the class that holds the method reference.
 *
 * <p>The endpoint owns its response records so the HTTP contract never exposes the view row
 * (API isolation, FR-010).
 */
@HttpEndpoint("/todo-summaries")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class TodoSummaryEndpoint {

  /** One assistant's to-do standing. API-owned; mirrors {@link TodoSummaryEntry} with shorter names. */
  public record TodoSummaryResponse(String username, int total, int open, int completed) {}

  /** Many assistants' standings. Always present; {@code summaries} is empty, never null, when none match. */
  public record TodoSummariesResponse(List<TodoSummaryResponse> summaries) {}

  private final ComponentClient componentClient;

  public TodoSummaryEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  /**
   * One user's to-do standing.
   *
   * <p>{@code 200} with the counts when a row exists — including all-zero counts for a user who
   * recorded items and then deleted them all. {@code 404} when the projection has no row for the
   * username at all: "no such assistant" is a different answer from "an assistant with nothing to do".
   *
   * <p>A blank or whitespace-only username is {@code 400}, rejected <strong>before</strong> the view
   * is queried — the same validation-first contract every other capability in this project honours,
   * here applied to a read surface. It is also a third distinct answer: {@code 400} means the question
   * was malformed, {@code 404} that a well-formed question had no match.
   */
  @Get("/by-user/{username}")
  public HttpResponse getByUser(String username) {
    // Guard first: an explicit check returning an error response, not a thrown exception (AGENTS.md).
    if (username == null || username.isBlank()) {
      return HttpResponses.badRequest("username must not be blank");
    }
    return componentClient
        .forView()
        .method(TodoSummaryView::getByUsername)
        .invoke(username)
        .map(this::toApi)
        .<HttpResponse>map(HttpResponses::ok)
        .orElseGet(HttpResponses::notFound);
  }

  /**
   * Every assistant still holding at least one open item — the cross-user question capability 6's
   * per-username assistant cannot answer.
   *
   * <p>Always {@code 200}. When nobody qualifies the body is {@code {"summaries":[]}}: an empty result
   * is a successful answer to a well-formed question, never a {@code 404}. Row order is unspecified
   * (the view declares no {@code ORDER BY}).
   */
  @Get("/with-open-work")
  public TodoSummariesResponse getWithOpenWork() {
    var entries =
        componentClient.forView().method(TodoSummaryView::withOpenWork).invoke().entries();
    return new TodoSummariesResponse(entries.stream().map(this::toApi).toList());
  }

  private TodoSummaryResponse toApi(TodoSummaryEntry entry) {
    return new TodoSummaryResponse(
        entry.username(), entry.totalCount(), entry.openCount(), entry.completedCount());
  }
}
