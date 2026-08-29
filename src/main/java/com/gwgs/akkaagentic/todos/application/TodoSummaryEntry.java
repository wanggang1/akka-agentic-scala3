package com.gwgs.akkaagentic.todos.application;

/**
 * One row of the {@code todo_summaries} view: a single assistant's to-do standing, keyed by username
 * (capability 11, the CQRS read side over capability 6's {@code TodoEntity}).
 *
 * <p><strong>Why this is a Java record and not a Scala case class</strong> (feature 013 research R3).
 * Two constraints compose; neither is the method-reference wall, which does not apply to data types
 * at all:
 *
 * <ol>
 *   <li><em>It crosses the SDK's internal serializer.</em> View rows are (de)serialized by
 *       {@code akka.javasdk.impl.serialization.Serializer}, the <em>internal</em> Jackson mapper that
 *       the public {@code DefaultScalaModule} hook in {@code Bootstrap} does not reach — the
 *       two-mapper boundary of README "Scala interop notes" §3. This forces the row to be
 *       Java-<em>shaped</em>. On its own it would <strong>not</strong> force Java authorship: the
 *       project ships Java-shaped types written in Scala on this same path ({@code HelpAnswer},
 *       {@code GreetingAgent.Result} — Jackson-annotated case classes).
 *   <li><em>Its consumer is the Java endpoint.</em> The querying endpoint must be Java because
 *       {@code ViewClient} is method-reference-only (research R1), and a Java record is the
 *       least-ceremony way to be Java-shaped for it — no Jackson annotations needed at all.
 * </ol>
 *
 * <p><strong>Historical note.</strong> This was originally documented as <em>forced</em>, because the
 * build could not compile a Java class against a Scala one: {@code maven-compiler-plugin} (parent POM)
 * ran before {@code scala-maven-plugin} (ours), so javac ran first and no Scala class file existed yet.
 * That was a latent build defect — cap-11's own endpoint references the Scala {@code TodoSummaryView},
 * so the capability did not build from a clean tree. It is fixed: {@code scala-maven-plugin} is now
 * bound to {@code process-resources} with {@code sendJavaToScalac=true}, so scalac runs first and javac
 * last. Both directions compile, and this record is Java by preference, not by necessity (research R3).
 *
 * <p>This is a deliberate, documented deviation from AGENTS.md's "define the row as an inner record of
 * the View" convention: the View is Scala, so its rows cannot live inside it.
 *
 * <p><strong>Invariant</strong>: {@code openCount + completedCount == totalCount}. It holds by
 * construction because every field is derived together by {@code TodoSummary.from}; nothing else ever
 * builds a row. An empty to-do list projects to {@code (0, 0, 0)} — a legitimate row, distinct from
 * <em>no row at all</em>.
 *
 * <p>Not a domain type, and never returned outward: the endpoint maps it to its own response record.
 *
 * @param username the {@code TodoEntity} id this row summarizes; also the row key
 * @param totalCount number of items on the list
 * @param openCount items still to do ({@code completed == false})
 * @param completedCount items done ({@code completed == true})
 */
public record TodoSummaryEntry(String username, int totalCount, int openCount, int completedCount) {}
