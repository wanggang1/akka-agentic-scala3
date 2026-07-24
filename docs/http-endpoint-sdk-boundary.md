# Design decision: HTTP endpoints and the Akka SDK boundary

**Decision:** Stay with **Option 1 — the Akka SDK's annotation-based endpoint model** — and keep
endpoints thin, delegating all behavior to pure-Scala domain/application code.

**Status:** accepted. Applies to all HTTP endpoints (capabilities 1–5) as a standing convention.

This note records *why* the SDK's Java-first HTTP shell is unavoidable (it is a framework boundary,
not a serialization boundary), the alternatives we considered and rejected, and the discipline we
adopt to keep the Java surface at the edge.

---

## Why the endpoint annotation is forced: the SDK is a runtime, not a library

The Akka **SDK** (`akka-javasdk`) is a framework with inversion of control — it does not get called,
it calls us:

- **The process *is* the runtime.** `exec:java` launches `kalix.runtime.AkkaRuntimeMain`, which reads
  the component descriptor, starts *its own* HTTP server (port 9000), and owns the durable store,
  task/agent lifecycle, sharding, ACLs, JWT, and tracing.
- **`ComponentClient` is injected by the runtime**, only into components it constructs — the closed
  list (per `AGENTS.md`) is *ServiceSetup, Endpoints, Agents, Consumers, TimedActions, Workflows.* It
  cannot be `new`-ed; it is wired to the runtime's internal message routing.
- **`@HttpEndpoint` + `@Get`/`@Post` is the registration handshake** with the runtime's server. There
  is no public seam to hand it an Akka HTTP scaladsl `Route`, so the native Scala routing DSL is not
  available within an SDK service.

So the annotation is **not** a serialization quirk we can convert away at the boundary (the way we do
null→`Option`, or Java-shaped wire types at the internal mapper). It is the handshake with a server
we do not own. That distinction is the whole point of this note.

## Why "plug the SDK in as a library" does not work in-process

This follows directly from what durability actually is on this platform (see the cap-3 / cap-5
findings): a task's durability, an agent's process state, and session memory are **provided by the
runtime**, not written in the component code — no `persist(...)`, no state field, nothing.

Therefore the components cannot be a passive jar you call:

- Extract `HelpDeskAgent` / `DraftAgent` into a library and call them from a non-SDK Scala HTTP server
  **without the runtime**, and you lose the runtime — i.e. durable tasks, the agent loop, session
  memory, orchestration. You are left with plain LLM-call wrappers.
- At that point the Akka SDK adds nothing; a plain LLM client library would do.

**The agentic value we want _is_ the runtime.** It has to host us; it cannot be something we call.

## Alternatives considered

There is no "SDK + Scala-native routes in one process" option. The three genuine topologies:

| Option | Scala-native HTTP? | Agentic durability | Cost |
|---|---|---|---|
| **1. Akka SDK** (chosen) | ❌ annotation shell | ✅ free from the runtime | Java-first endpoint model |
| 2. Akka *libraries* (akka-http scaladsl, akka-persistence, akka-stream) | ✅ fully | ⚠️ build it yourself | Reimplement agents/tasks/memory on a plain LLM client |
| 3. Two services | ✅ at the edge | ✅ in the back-end | Scala front calls an SDK back-end over HTTP/gRPC — network hop, two deployables, thin SDK endpoints still required on the back |

`CLAUDE.md` marks the seam: *"These guidelines apply only to the Akka SDK and do not cover the
lower-level Akka libraries."* Option 2 is classic Akka (a Scala-native toolkit); Option 1 is the
opinionated framework on top that trades Scala-nativeness for the agentic runtime.

**We chose Option 1.** The project's purpose is to prove idiomatic Scala *on the Akka SDK* and to use
its agentic primitives (Autonomous Agents, Tasks, session memory, workflows). Options 2 and 3 either
discard those primitives or fragment a single service into two; neither serves the goal. We accept the
forced annotation shell and contain it.

## The discipline we adopt (thin endpoints)

Because the shell is forced, the goal is to make it *only* a shell — an anti-corruption layer, with
the SDK's Java surface quarantined at the edge and everything behavioral in pure Scala.

```
com.gwgs.akkaagentic.<cap>.domain       pure Scala: ADTs, validation, FSM — NO Akka imports
com.gwgs.akkaagentic.<cap>.application   SDK components (Agents, Task defs)
                                         + a Scala service PORT (pure types)
                                         + a ComponentClient ADAPTER implementing the port
com.gwgs.akkaagentic.<cap>.api           thin @HttpEndpoint: parse -> call port -> translate
```

**Port** — pure Scala, no Akka type in the signature:

```scala
trait ApprovalService:
  def submit(question: Option[String]): Either[String, String]   // Left = validation message
  def state(caseId: String): CaseProgress                        // domain ADT
  def approve(caseId: String, note: Option[String]): Decision    // domain ADT
  def reject(caseId: String, note: Option[String]): Decision
```

**Adapter** — the *only* place SDK behavioral types live; constructed from the injected
`ComponentClient`:

```scala
final class ComponentClientApprovalService(componentClient: ComponentClient) extends ApprovalService:
  // all forTask / forAutonomousAgent / TaskStatus / TaskOutcome adaptation lives here
```

**Endpoint** — translation only; imports just `HttpResponses`, the annotations, and domain/DTO types:

```scala
@Post("/approvals")
def submit(r: SubmitRequest): HttpResponse =
  service.submit(r.question) match
    case Left(msg)     => HttpResponses.badRequest(msg)
    case Right(caseId) => HttpResponses.accepted(CaseAccepted(caseId))
                            .addHeader(Location.create("/approvals/" + caseId))
```

### What stays forced (be honest — this is ~90% containment, not 100%)

- **The routing annotation stays.** `@Post("/approvals")` cannot be replaced by a Scala routing DSL.
- **`akka.http.javadsl.HttpResponse` stays** as the endpoint return type. (A scaladsl-built response
  *is-a* javadsl one — `scaladsl.model.HttpResponse extends javadsl.model.HttpResponse` — so scaladsl
  would satisfy the type, but we still prefer `akka.javasdk.http.HttpResponses`: it marshals the
  response **body** through the `Bootstrap`-registered `DefaultScalaModule` mapper, the feature-003
  two-mapper finding. Hand-building a scaladsl response means owning the entity bytes yourself for no
  gain.)
- **Ser/deser of request/response bodies is already idiomatic Scala** — `Option`-typed DTOs via the
  public `JsonSupport` mapper (feature 003). That boundary *was* convertible and we converted it. The
  routing shell was not, and this note explains why.

### What we gain

- SDK behavioral coupling contained to one named adapter per capability; the endpoint reads as
  pure-Scala intent.
- An SDK API change has a one-file blast radius.
- A uniform template for every capability's endpoint.

### What we do *not* over-claim

- Not a large testability win: the interesting logic (FSM, validation, decision guard) is already in
  `domain` and unit-tested with no runtime (cap-5 `ApprovalCase` / `TaskOutcome`). The adapter still
  needs the TestKit, because it talks to the runtime.
- Adds one trait + one class per capability. Worth it for containment and uniformity; skip it where an
  endpoint is already a trivial pass-through.

## Adoption plan

1. Finish capability 5 on the current shape (endpoint holds the `ComponentClient` calls directly for
   now — the domain FSM is already extracted, which is the valuable half).
2. Introduce the port/adapter split as its own change, with **cap-5 as the reference** (smallest step,
   since its logic is already in `domain`).
3. Retrofit caps 1–4 opportunistically — they are merged and stable, so this is cosmetic and may lag.
4. Record the outcome as a "Scala interop notes" entry in `README.md`, in the style of the other
   Java-first findings.

## Relationship to the project's other findings

This is the HTTP-layer companion to the recurring through-line (README interop notes, `FINDINGS.md`):
the SDK is Java-first in identifiable places, and idiomatic Scala is achieved by **converting at the
boundary**. The new nuance here: some boundaries are *serialization* boundaries (convertible — wire
types, null→`Option`) and some are *framework* boundaries (not convertible — the routing shell,
`ComponentClient` injection). The endpoint annotation is the latter, which is why it stays.
