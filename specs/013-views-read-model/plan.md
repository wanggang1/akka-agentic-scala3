# Implementation Plan: To-do Read Model — a View over the assistant's to-dos (cap-11)

**Branch**: `013-views-read-model` | **Date**: 2026-08-28 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/013-views-read-model/spec.md`

## Summary

Add the one core Akka component family this project has never built: a **View**. A `TodoSummaryView`
projects capability 6's `TodoEntity` state changes into one summary row per username (total / open /
completed counts), and a read-only HTTP endpoint exposes two queries over it — a keyed lookup and a
filtered cross-user "who still has open work?". Nothing about cap-6 changes, and no model is called
anywhere.

**Technical approach, settled by Phase 0 research** ([research.md](research.md)):

- **The View component is Scala** (R2) — its `TableUpdater` lives in the View's **companion object**,
  which is the only Scala nesting form that compiles to the `public static`, zero-arg-constructor
  nested class the SDK reflects on. A Scala *inner* class was proven unconstructable.
- **The querying endpoint is Java** (R1) — `ViewClient` exposes only `method(akka.japi.function.Function…)`
  with no `dynamicCall`, and that `Function` is `Serializable`, so a Scala lambda's `$anonfun` never
  resolves. Confirmed by bytecode; the project's bet was right.
- **Row records are Java** (R3) — they cross the SDK's internal serializer *and* are consumed by the
  Java endpoint, and README §8 forbids a Java→Scala dependency.
- The counts derivation is a **pure Scala domain function**, unit-tested with no runtime.

## Technical Context

**Language/Version**: Scala 3.3.8 LTS (primary) + Java 21 (quarantined: endpoint + row records)
**Primary Dependencies**: `akka-javasdk` 3.6.3 — **no new dependency**
**Storage**: the runtime-managed view store, projected from `TodoEntity` (`KeyValueEntity`) state changes
**Testing**: JUnit 5 + AssertJ + Awaitility; `TestKitSupport`; **no `TestModelProvider` anywhere**
**Target Platform**: Akka runtime, local `exec:java` and deployed alike
**Project Type**: single Akka service (mixed Scala/Java module)
**Performance Goals**: none specified; the view is eventually consistent by design
**Constraints**: fully offline — no model, no API key, no network (FR-011, FR-012)
**Scale/Scope**: 3 new production classes + 2 records, 3 test classes, 1 descriptor entry, 4 doc updates

**No NEEDS CLARIFICATION remain.** R6 is a single open *implementation detail* (no-match behavior of a
keyed query) carried with a decided fallback that satisfies FR-004 either way — it is resolved by the
first test run, not by assumption.

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 — still passing.*

| Principle | Status | How |
|---|---|---|
| **I. Akka SDK First** | ✅ | The read model is an SDK `View` — the sanctioned primitive for cross-key queries. No third-party store, no custom projection. **No new dependency** (nothing added to `pom.xml`). |
| **II. Design Principles** — domain independence | ✅ | Counts derivation is a pure function in `todos/domain`, with no Akka import, unit-tested without a runtime. |
| **II.** — API isolation | ✅ | The endpoint defines its own response types (FR-010); it never returns the view row or `TodoList` outward. |
| **II.** — single responsibility | ✅ | View projects and queries; endpoint adapts HTTP; domain derives counts. Read-only, so no overlap with cap-6's write path. |
| **II.** — descriptive naming | ✅ | `TodoSummaryView`, `TodoSummaryEntry`, `TodoSummaryEndpoint` — no generic `Service`/`Manager`. |
| **III. Test Coverage** | ✅ | Pure unit test for the derivation; view integration test for the projection; endpoint integration test for the HTTP contract. Cap-6's existing tests must remain untouched and green (SC-006). |
| **IV. Simplicity** | ✅ | Counts-only row, exactly two queries, no sorting/paging/search, no write path, no new packages beyond the one capability package. |

**Post-Phase-1 re-check**: unchanged. The one deviation (row records as top-level Java records rather
than inner records of the View, contrary to AGENTS.md's convention) is recorded in Complexity Tracking
with its justification.

## Project Structure

### Documentation (this feature)

```text
specs/013-views-read-model/
├── spec.md              # Phase -1 (/akka.specify)
├── plan.md              # This file
├── research.md          # Phase 0 — R1..R6, bytecode + compile-spike evidence
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   └── http-api.md      # Phase 1 — the two read endpoints
├── checklists/
│   └── requirements.md  # spec quality checklist (passed)
└── tasks.md             # Phase 2 (/akka.tasks — NOT created here)
```

### Source Code (repository root)

```text
src/main/scala/com/gwgs/akkaagentic/todos/
├── domain/
│   └── TodoSummary.scala          # SCALA. Pure: TodoList -> (total, open, completed). No Akka import.
└── application/
    └── TodoSummaryView.scala      # SCALA. class TodoSummaryView extends View  +  companion object
                                   #   holding `class Updater extends TableUpdater[TodoSummaryEntry]`
                                   #   (@Table + @Consume.FromKeyValueEntity(classOf[TodoEntity]))
                                   #   and the two @Query methods on the View class.

src/main/java/com/gwgs/akkaagentic/todos/
├── application/
│   ├── TodoSummaryEntry.java      # JAVA record — the view row (R3)
│   └── TodoSummaryEntries.java    # JAVA record — multi-row wrapper: List<TodoSummaryEntry> entries
└── api/
    └── TodoSummaryEndpoint.java   # JAVA — forced by ViewClient method-refs (R1). Own response types.

src/main/resources/META-INF/akka-javasdk-components_com.gwgs_akka-agentic-scala3.conf
                                   # + new `view` key; + endpoint under `http-endpoint` (R4)

src/test/scala/com/gwgs/akkaagentic/todos/
├── domain/TodoSummaryTest.scala                     # SCALA, pure, no runtime
└── api/TodoSummaryEndpointIntegrationTest.scala     # SCALA — httpClient only, no method refs
src/test/java/com/gwgs/akkaagentic/todos/
└── application/TodoSummaryViewIntegrationTest.java  # JAVA — queries the View via componentClient (R1)
```

**Structure Decision**: a new capability package `com.gwgs.akkaagentic.todos.*`, split by language
exactly along the boundary research proved. It follows the project's established shape (cap-6
`a2a.*`, cap-8 `docs.*`) and keeps the Java quarantine to **the endpoint plus the two row records** —
the class that holds the method reference and the types it must not reach into Scala for.

**Untouched by design** (SC-006): `a2a/application/TodoEntity.java`, `a2a/application/TodoTools.java`,
`a2a/domain/Todo.java`, `a2a/domain/TodoList.java`, `a2a/application/PersonalAssistantAgent.scala`,
`a2a/api/PersonalAssistantEndpoint.scala`, and every existing test.

## Implementation Sequence

Ordered so the riskiest, most informative step comes first — the project's habit of settling interop
before building on it.

1. **Domain** — `TodoSummary` derivation + its pure Scala unit test. No SDK involved; runs in seconds.
2. **Row records** — the two Java records. Trivial, but they are the shared vocabulary everything else
   compiles against.
3. **The View (Scala)** — updater in the companion object per R2, plus both `@Query` methods; add the
   `view` key to the descriptor. **This is where R6 gets settled**: run the Java view integration test
   and observe what a no-match keyed query actually does, then keep `Optional` or take the documented
   wrapper fallback.
4. **View integration test (Java)** — publishes `TodoList` states via `withKeyValueEntityIncomingMessages`
   + `publish(todoList, username)`, then queries through `componentClient.forView()` with Awaitility.
5. **Endpoint (Java)** — the two GETs, validation-first, own response types.
6. **Endpoint integration test (Scala)** — `httpClient` only, so it stays Scala. Covers 200 / 404 /
   400 / empty-collection.
7. **Docs** — README §13 + cap-11 usage section, ROADMAP row + "You are here", FINDINGS.md through-line.

## Testing Strategy

| Test | Language | Why that language | Covers |
|---|---|---|---|
| `TodoSummaryTest` | Scala | pure domain, no SDK | counts arithmetic, empty list, all-complete, all-open |
| `TodoSummaryViewIntegrationTest` | **Java** | queries the View → `ViewClient` method refs (R1) | projection updates, convergence, cross-user filter, isolation |
| `TodoSummaryEndpointIntegrationTest` | Scala | `httpClient` only — no method reference | 200, 404 unknown, 400 blank, empty collection |

**Notes carried in from prior features:**

- `responseBodyAs` **throws on non-2xx**, so the 400/404 assertions must omit it and assert on
  `response.status()` alone (memory: *Akka httpClient failure-status testing*).
- Views are eventually consistent — every read-after-write assertion goes through `Awaitility.await()`,
  never a bare assert.
- **First capability in this project with no `TestModelProvider` in any test.** Worth calling out
  explicitly in the docs: `mvn verify` proves this capability end to end with no model, mocked or live.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Row types are **top-level Java records**, not inner records of the View (AGENTS.md convention) | The Java endpoint consumes them, and README §8 forbids a Java→Scala dependency; nesting them in the Scala View would force exactly that | Nesting in the View (convention) — rejected: creates the forbidden dependency direction. Authoring them as Jackson-annotated Scala case classes — rejected for the same reason |
| The capability spans **two languages** | `ViewClient` is method-reference-only (R1, bytecode-proven); the endpoint cannot be Scala | All-Java — rejected: R2 proved the View itself works in Scala, and an all-Java capability would forfeit the finding. All-Scala — impossible |
