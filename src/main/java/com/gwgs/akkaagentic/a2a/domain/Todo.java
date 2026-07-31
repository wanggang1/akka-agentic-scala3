package com.gwgs.akkaagentic.a2a.domain;

/**
 * A single to-do item held by a personal assistant (capability 6, agent-to-agent delegation).
 *
 * <p>Java record on purpose: it is an element of {@link TodoList}, the {@code TodoEntity}
 * KeyValueEntity state, which is serialized by the SDK's <em>internal</em> Jackson mapper (the one
 * the public Scala module hook does not reach — see README "Scala interop notes" §3). Component
 * state must therefore stay Java-shaped, so the whole TODO subsystem (this record, {@link TodoList},
 * the entity, and its tool object) is Java while the agent, forwarding tool, and endpoint stay Scala.
 *
 * <p>Ids are assigned by {@link TodoList} and are unique within a single assistant (per username).
 */
public record Todo(int id, String description, boolean completed) {}
