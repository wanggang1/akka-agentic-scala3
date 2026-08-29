package com.gwgs.akkaagentic.todos.application;

import java.util.List;

/**
 * The result of a {@code todo_summaries} query that can match many rows.
 *
 * <p>The SDK requires this wrapper shape: a multi-row query must return a record with a single list
 * field, selected as {@code SELECT * AS entries FROM …}. A bare {@code SELECT *} returning a list is
 * not valid (AGENTS.md).
 *
 * <p>{@code entries} is empty, never null, when nothing matches — "no assistant has open work" is a
 * successful answer, not an error or a not-found.
 *
 * <p>Java for the same reason as {@link TodoSummaryEntry}: it crosses the SDK's internal serializer
 * <em>and</em> its consumer is the Java endpoint, which this build cannot compile against a Scala
 * type (javac runs before scalac — see {@code TodoSummaryEntry}'s javadoc and feature 013 research R3).
 */
public record TodoSummaryEntries(List<TodoSummaryEntry> entries) {}
