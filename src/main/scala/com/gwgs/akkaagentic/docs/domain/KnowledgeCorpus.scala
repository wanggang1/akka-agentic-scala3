package com.gwgs.akkaagentic.docs.domain

/** The fixed demo corpus for capability 8 (RAG). A handful of hand-written passages describing this
  * project's own capabilities and Scala-on-Akka interop findings — a self-referential corpus, chosen
  * so that in-corpus vs out-of-corpus questions are easy to construct and verify. The subject matter
  * is not load-bearing to the capability; the point is that each passage is a semantically distinct
  * unit with a unique [[Passage.source]] label, so retrieval can discriminate by meaning.
  *
  * Pure data — no Akka, no randomness — so retrieval is stable across runs (FR-009).
  */
object KnowledgeCorpus:

  val passages: List[Passage] = List(
    Passage(
      "cap-1-greeting",
      "The greeting agent takes a user, a message, and an optional timezone and returns a " +
        "structured greeting with a tone and a time of day. It uses a function tool to report the " +
        "caller's current time of day and adapts its reply to the message's detected intent."
    ),
    Passage(
      "cap-3-help-desk",
      "The autonomous help-desk agent answers a question through a model-driven loop. Given a " +
        "question, the model decides on its own whether to consult a knowledge-base function tool " +
        "before completing a typed task carrying the answer, a category, cited topics, and a " +
        "confidence score. No code orders the steps — the runtime drives the model until the task " +
        "completes or fails."
    ),
    Passage(
      "cap-4-session-memory",
      "The multi-turn chat agent holds a conversation using the runtime's session memory, keyed by " +
        "the session id in the request path. Earlier turns are replayed as context on the next call " +
        "with the same id, so the same session id is one continuous conversation while a different " +
        "id is a separate, isolated one."
    ),
    Passage(
      "cap-5-approval-gate",
      "The human-in-the-loop approval gate drafts a candidate reply, then pauses at a gate only a " +
        "person can release. It is a three-task dependency chain — draft, then an unassigned " +
        "approval gate, then publish — wired by task dependencies rather than a workflow. Approving " +
        "releases the publish step; rejecting fails the gate and auto-cancels publishing, so nothing " +
        "is ever published without approval."
    ),
    Passage(
      "cap-6-delegation",
      "Each username has its own personal assistant that remembers the conversation, keeps a " +
        "personal to-do list, and can delegate to another user's assistant by relaying that " +
        "assistant's reply. The to-do list is durable state held in a key-value entity keyed by " +
        "username; delegation targets another assistant by runtime username string."
    ),
    Passage(
      "cap-7-activity-coordinator",
      "The activity coordinator is an autonomous agent that suggests activities by delegating to two " +
        "request-based specialists — a weather specialist and an activity specialist — through the " +
        "built-in delegation capability, then synthesizing their input into one typed suggestion. " +
        "The model chooses which specialists to consult at runtime, by class."
    ),
    Passage(
      "interop-method-ref-wall",
      "Whether a component can be authored in Scala on this Java-first SDK depends on whether its " +
        "client offers a dynamicCall escape hatch. The Agent, AutonomousAgent, and Task clients have " +
        "dynamicCall, so they are Scala-friendly. The Workflow and event-sourced-entity clients are " +
        "keyed on Java method references with no dynamicCall, so components that need them are " +
        "quarantined into Java. This is called the method-reference wall."
    ),
    Passage(
      "interop-two-mapper",
      "The SDK uses two Jackson mappers. HTTP endpoint request and response bodies go through a " +
        "Scala-aware mapper, so they can be idiomatic annotation-free case classes with Option " +
        "fields. Component-to-component payloads — agent request and result types, entity events and " +
        "state — go through a separate internal mapper that is not Scala-aware, so those types must " +
        "stay Java-shaped with Jackson annotations."
    ),
    Passage(
      "durability-tasks",
      "An autonomous agent's task is a durable record. The runtime persists the task's id, status, " +
        "and typed result, along with the agent's process state, as a model-driven loop progresses, " +
        "and recovers them after a crash or restart. Because the task is already durable and " +
        "queryable by its id, no wrapping workflow is needed for durability."
    )
  )
