# Feature Specification: To-do Read Model — a View over the assistant's to-dos (cap-11)

**Feature Branch**: `013-views-read-model`
**Created**: 2026-08-28
**Status**: Draft
**Input**: User description: "Capability 11 — Views / read-model (CQRS read side). Build the one core Akka component family this project has never built: a View. Source of data is cap-6's TodoEntity (a KeyValueEntity keyed by username whose state is a TodoList). Today to-dos are reachable only through the assistant agent; a read-model gives them a queryable, cross-user read surface with no write path. Known constraint: a KVE-sourced View's updater emits one row per entity update, so a username's list cannot fan out into per-item rows — the spec must choose and justify a row shape. Read-only and additive: no change to cap-6's write path, no new model calls. The required research item is the interop bet: is the View client method-reference-only (forcing View + endpoint into Java, the CQRS counterpart to cap-2's workflow and cap-6's entity), or is it Scala-clean?"

## Context

This is capability 11 in a learning sandbox that explores Akka agentic features on Scala 3 over the
Java-first Akka SDK, one concept at a time. Capabilities 1–10 covered agents, workflows, autonomous
agents, session memory, human-in-the-loop, delegation, RAG, and both sides of MCP. **A `View` — the
CQRS read side, and one of the core Akka component families — has never been built here.** Cap-11
closes that gap.

Two things make this capability distinctive in the series:

1. **It is the cleanest remaining data point for the project's through-line.** Every capability has
   been framed by one question: *is this Scala-clean, or does it hit the method-reference wall?*
   (See `FINDINGS.md`.) The wall has proven to be a **client** property: clients keyed on Java method
   references with no `dynamicCall` escape hatch (Workflow, event-sourced entity, key-value entity)
   force their callers into Java; everything else (agents, tasks, dependency injection, MCP) stays
   Scala. The **bet** for cap-11 is that the View query client behaves like the entity clients —
   method-reference-only — which would push the View *and its calling endpoint* into Java, making
   this the CQRS-read counterpart to cap-2's Java workflow and cap-6's Java to-do entity. Settling
   that bet with evidence is a first-class deliverable, not a footnote.

2. **It is the first capability with no model call at all.** Every prior capability centered on an
   LLM. A read model is pure projection and query: deterministic, fully offline-testable end to end,
   with no test model provider and no live smoke test needed for its core behavior. That is worth
   recording as its own observation about where the SDK's testability story is strongest.

The data source already exists. Capability 6 gives each username a personal assistant with a
persisted to-do list held in a key-value entity keyed by username. Cap-6 deliberately shipped **no
direct to-do HTTP surface** (its FR-009): to-dos are reachable only *through* the assistant, because
the entity client could not be called from a Scala endpoint. A read model is exactly the missing
piece — it makes to-dos queryable, across users, **without** adding a write path or touching the
assistant.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Look up one assistant's to-do standing (Priority: P1)

An operator (or a support tool, or a dashboard) wants to know where a single named user's to-do list
stands — how many items they have, how many are still open, how many are done — without going
through the assistant and without asking a language model. They ask the read surface by username and
get an immediate, factual answer.

**Why this priority**: This is the smallest slice that proves a working read model end to end: the
projection is populated from entity updates and is queryable by key. It is the MVP on its own — and
it alone settles the capability's interop question, because it exercises both the projection and the
query client.

**Independent Test**: Drive a user's to-do list through the existing write path (or by publishing
entity state directly), then query the read surface for that username and confirm the returned
counts match the list's actual contents.

**Acceptance Scenarios**:

1. **Given** a user whose to-do list has items recorded, **When** the read surface is queried for
   that username, **Then** it returns that user's to-do standing with total, open, and completed
   counts matching the list.
2. **Given** a user who has never recorded a to-do, **When** the read surface is queried for that
   username, **Then** it reports that there is nothing on record (a not-found result), rather than
   inventing an empty summary.
3. **Given** a user whose to-do list changes (an item is added, completed, or deleted), **When** the
   read surface is queried again shortly afterwards, **Then** the returned counts reflect the change.

---

### User Story 2 - Find every assistant that still has open work (Priority: P2)

An operator wants a cross-user picture: which assistants still have unfinished to-dos. This is a
question no existing surface can answer — the assistant only ever speaks for one username at a time,
and there is no way to ask "across all users, who has open items?" without a read model.

**Why this priority**: This is the part that justifies a *read model* rather than a direct entity
read. A single-key lookup could in principle be served by the entity itself; a **cross-user query
with a filter** cannot. It is the capability's real payoff, but it depends on the projection that
User Story 1 already establishes.

**Independent Test**: Record to-do lists for several users — some with open items, some with all
items completed, some with none — then query the "has open work" surface and confirm exactly the
users with at least one open item are returned.

**Acceptance Scenarios**:

1. **Given** several users with to-do lists in varying states, **When** the read surface is asked
   for assistants with open work, **Then** it returns exactly those users with at least one open
   item, each with its counts.
2. **Given** a user whose every item is completed, **When** the same query runs, **Then** that user
   is not included in the results.
3. **Given** no user anywhere has an open item, **When** the same query runs, **Then** an empty
   result set is returned successfully — not an error and not a not-found.

---

### User Story 3 - Reject a malformed lookup before doing any work (Priority: P3)

A caller asks for a blank or whitespace-only username. The service rejects it immediately with a
clear client error, before any query runs.

**Why this priority**: Consistency with every other capability's validation-first contract. It is the
smallest slice and the least novel, but the project's surfaces are uniform about it and this one
should not be the exception.

**Independent Test**: Ask the read surface for a blank/whitespace-only username; confirm a client
validation error and that no query was executed.

**Acceptance Scenarios**:

1. **Given** the read surface, **When** a caller asks for a blank or whitespace-only username,
   **Then** the request is rejected with a validation error and no query runs.
2. **Given** the read surface, **When** a caller asks for a username that is well-formed but unknown,
   **Then** the result is a not-found response, clearly distinct from the validation error above.

---

### Edge Cases

- **A never-touched user vs. a user with an empty list.** A username whose assistant has never
  recorded anything has no row at all (not found). A username who recorded items and then deleted
  them all *does* have a row, with all counts zero. These are different answers to different
  questions, and both must be reachable.
- **Eventual consistency.** The read model is a projection updated asynchronously after the write.
  A read taken immediately after a change may briefly show the previous counts. This is expected
  behavior of a read model, not a defect — the requirement is that it converges, and any test must
  wait for convergence rather than assume it is instantaneous.
- **All items completed.** Open count reaches zero; the user must disappear from the "has open work"
  result while remaining findable by direct username lookup.
- **No users at all / no open work at all.** The cross-user query returns an empty collection with a
  success result, never an error or a not-found.
- **Redelivered or repeated updates.** Duplicate delivery of the same state change must not corrupt
  the counts. Note this is the platform's concern, not ours: entity-sourced read models get
  exactly-once delivery with built-in per-entity sequence-number deduplication, and the record is
  replaced wholesale on each update rather than incremented — so there is no accumulator to
  double-count. The requirement is simply that the projection stay a pure function of the latest
  state, never a running total.
- **Username casing and surrounding whitespace.** Usernames are treated exactly as the write side
  keys them — matching is exact, with no case folding — so a lookup that differs only in case is a
  miss (a not-found), not a match.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST maintain a queryable read model of to-do standing derived from the
  existing per-user to-do state, updated automatically as that state changes.
- **FR-002**: The read model MUST hold **one record per username**, carrying at minimum the username,
  the total number of items, the number of open (not completed) items, and the number of completed
  items.
- **FR-003**: Users MUST be able to look up a single username's to-do standing and receive its
  counts.
- **FR-004**: A lookup for a username with no record MUST return a not-found result, distinguishable
  from both a validation error and a successful empty-counts record.
- **FR-005**: Users MUST be able to query for **all** usernames that have at least one open item, and
  receive a collection of their records.
- **FR-006**: The cross-user query MUST return a successful empty collection when no username
  qualifies.
- **FR-007**: A blank or whitespace-only username MUST be rejected with a client validation error
  before any query is executed.
- **FR-008**: The read surface MUST be **read-only**: it MUST NOT expose any way to create, modify,
  or delete a to-do, and MUST NOT alter the existing write path or the assistant's behavior.
- **FR-009**: The read model MUST converge with the underlying to-do state after a change, and the
  system's behavior MUST NOT depend on that convergence being immediate.
- **FR-010**: The read surface MUST define its own response types rather than exposing the internal
  to-do state shape.
- **FR-011**: The capability MUST NOT invoke a language model anywhere in its request path.
- **FR-012**: The capability MUST be verifiable offline with no live model, no API key, and no
  network access, including the projection behavior and both queries.
- **FR-013**: The project MUST record the resolved interop finding for this component family: whether
  the read model's own class and its query client can be authored in Scala or are forced into Java by
  the method-reference wall, whether the read model's record type must be Java-shaped for the
  internal serializer, and how the component is declared for discovery — with the evidence that
  settled it.

### Key Entities *(include if feature involves data)*

- **To-do standing (read model record)**: One record per username summarizing that user's to-do
  list — the username it is keyed by, the total item count, the open item count, and the completed
  item count. It is derived, never authored: it exists only as a projection of the per-user to-do
  state, and is replaced wholesale whenever that state changes.
- **Per-user to-do list (existing, unchanged)**: Capability 6's persisted list of items per username,
  each item carrying an id, a description, and a completed flag. This capability **reads** it and
  changes nothing about it.

## Assumptions

- **Record shape is counts-only, one row per username.** The underlying store holds a *list* per
  username, and a projection driven by state updates produces **one record per update of that
  username's state** — it cannot fan a single user's list out into one record per item. Rather than
  work around that, the read model embraces it: the record is a per-username **summary** (total /
  open / completed). This is the simplest shape that still demonstrates real query capability — a
  keyed single-record lookup *and* a filtered multi-record query — and it keeps the constraint
  visible instead of hidden. Carrying the item descriptions inside the record is deliberately **not**
  done (YAGNI; the assistant already answers "what is on my list?", and per-item querying would
  require a different write-side shape, noted under Out of Scope).
- **Read-only and additive.** Nothing about capability 6 changes: not the entity, not its state
  shape, not the assistant, not the tool object that reaches it. The read model is a pure addition.
  In particular, cap-6's decision to expose no to-do *write* endpoint stands.
- **Two queries, not a query language.** A keyed lookup and one filtered cross-user query are enough
  to demonstrate the component family. Sorting, paging, free-text search, and date ranges are out of
  scope.
- **The read surface is a small set of read-only HTTP GETs** with its own response types, following
  the same conventions as every other capability in the project (explicit access control, API-specific
  types, validation before work).
- **Eventual consistency is accepted and made visible**, not hidden behind synchronous reads. Tests
  wait for convergence; documentation states the behavior plainly.
- **The implementation language of the read model and its endpoint is not assumed.** The project's
  bet is that the query client is method-reference-only and therefore forces Java, but that is a
  research question to be settled against the SDK before the code is written — and whichever way it
  falls is a recorded finding, not a surprise. If it does force Java, the Java quarantine is kept as
  small as the project's established rule requires (the component and its immediate caller only).
- **No model, no key, no network.** Unlike every prior capability, there is no generative half here,
  so the whole capability — projection and both queries — is expected to be provable offline. No live
  smoke test is expected to be needed for correctness, though the surface may still be exercised
  manually for documentation.

## Out of Scope

- Any write path for to-dos (create / complete / delete via HTTP). To-dos are still changed only
  through the assistant.
- Per-item querying ("show me every open item across all users", "find items matching text"). That
  needs a different write-side shape — one item per record — which would mean changing capability 6's
  state model, and this capability is explicitly additive.
- Sorting, pagination, and result limits on the cross-user query.
- Any read model over other capabilities' state (sessions, tasks, approvals, agent interactions).
- Any language-model involvement, including summarizing or ranking results.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After a user's to-do list is changed, a lookup of that username returns counts matching
  the list's actual contents (total, open, completed) once the read model has converged.
- **SC-002**: A lookup of a username with nothing on record returns a not-found result, and a lookup
  of a user whose items were all deleted returns a record with all counts zero — the two outcomes are
  distinguishable.
- **SC-003**: With several users in differing states, the cross-user query returns exactly the users
  holding at least one open item, and excludes users whose items are all completed.
- **SC-004**: When no user holds an open item, the cross-user query returns a successful empty
  collection rather than an error or a not-found.
- **SC-005**: A blank or whitespace-only username is rejected with a client validation error, and no
  query is executed for it.
- **SC-006**: The read surface exposes no operation that can create, change, or remove a to-do, and
  the existing assistant behavior is unchanged (its tests continue to pass untouched).
- **SC-007**: The entire capability — projection updates and both queries — is verified by automated
  tests that run with no live model, no API key, and no network access.
- **SC-008**: The interop question is answered and recorded: a clear project finding states whether
  this component family and its query client are Scala-clean or forced into Java by the
  method-reference wall, with the evidence that settled it, alongside the existing findings for
  workflows, entities, tasks, dependency injection, and MCP.
