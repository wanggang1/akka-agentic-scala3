# Research: Scala-native JSON for wire types

Phase 0 findings. Every unknown from the spec/Technical Context is resolved below; there are no
remaining `NEEDS CLARIFICATION` items.

## R1 — How is a `@Setup`/`ServiceSetup` class discovered under a hand-maintained Scala descriptor? (THE CRUX)

**Decision**: Add a **top-level** entry to the existing hand-maintained descriptor:

```hocon
akka.javasdk.service-setup = "com.gwgs.akkaagentic.application.Bootstrap"
```

as a **sibling of** `akka.javasdk.components { … }` — not a key inside it — and a **single
string**, not a list.

**Rationale**: Confirmed directly from the SDK bytecode (3.6.0), not from docs (the docs show
`@Setup` usage but omit the descriptor key):

- `akka.javasdk.tooling.processor.ComponentAnnotationProcessor` emits the setup class under the
  component-type token `service-setup` (alongside `http-endpoint`, `agent`, …). Because this
  processor is a `javac` annotation processor, it only scans **Java** sources and never sees our
  Scala `Bootstrap` — the same gap that forces us to hand-maintain component entries.
- `akka.javasdk.impl.ComponentLocator$` defines two distinct paths:
  - `DescriptorComponentBasePath = "akka.javasdk.components"` — components, read with
    `Config.getStringList(...)` per type key.
  - `DescriptorServiceSetupEntryPath = "akka.javasdk.service-setup"` — the setup class, guarded by
    `Config.hasPath(...)` and read with `Config.getString(...)` (**single value**).

So the setup class is located exactly like our components (via the descriptor `ComponentLocator`
reads), which also means the **TestKit** picks it up — its `onStartup` runs during test service
startup, registering the module for the offline suite too.

**Alternatives considered**:
- *Put it under `akka.javasdk.components.service-setup = [...]`* — rejected: `ComponentLocator`
  reads the setup class from the top-level `akka.javasdk.service-setup` path, not from the
  components map; a list there would not be read as the setup class.
- *Rely on annotation-processor generation* — impossible: the processor doesn't scan Scala (the
  project's founding constraint).
- *Register the module without a `ServiceSetup`* (e.g. a static initializer) — rejected: no
  guaranteed run-once-at-startup hook, fights the SDK lifecycle, and `ServiceSetup` is the
  documented, SDK-first mechanism.

## R2 — Is the Scala Jackson module available, and is it already registered?

**Decision**: Use `com.fasterxml.jackson.module.scala.DefaultScalaModule`, registered explicitly at
startup. No dependency change.

**Rationale**: `mvn dependency:tree` shows `jackson-module-scala_2.13:2.21.2` on the compile
classpath, exactly matching `jackson-databind:2.21.2`. A throwaway spike against
`JsonSupport.getObjectMapper()` showed the registered modules are only `jdk8`, `jsr310`,
`parameter-names`, and one `SimpleModule` — **the Scala module is present but not registered**. So
the work is a one-line registration, not a dependency addition.

**Alternatives considered**:
- *Add our own `jackson-module-scala` dependency* — rejected: it's already transitively present;
  a second copy risks a version conflict with the SDK's pinned Jackson (constitution I).
- *`findAndRegisterModules()` (ServiceLoader auto-registration)* — rejected as the mechanism: the
  spike proves it is not happening on the SDK mapper; explicit `registerModule` is deterministic.

## R3 — Does the `_2.13` module work for Scala 3.3.4 case classes?

**Decision**: Yes; rely on it and prove it with the US1 round-trip test.

**Rationale**: `jackson-module-scala` publishes for Scala 2.11/2.12/2.13; there is no `_3` artifact.
Scala 3 is binary-compatible with the Scala 2.13 standard library, and the module's runtime
reflection over case-class accessors/`Option` works for ordinary Scala 3 case classes. This is
exactly the risk the P1 story exists to retire — an executable round-trip test
(`Some`/`None`/absent) is the acceptance gate, so we do not take it on faith.

**Alternatives considered**: none viable (no `_3` build exists; hand-writing serializers would
defeat the purpose).

## R4 — Serialization semantics for `Option` and effect on existing/annotated types

**Decision**: Registering `DefaultScalaModule` is **additive**. Annotation-free Scala case classes
gain native handling (present → `Some`, absent/null → `None`); existing Java-shaped types that keep
`@JsonCreator`/`@JsonProperty` continue to work unchanged.

**Rationale**: Jackson modules compose; the Scala module adds Scala introspection without removing
the `jdk8`/`jsr310`/`parameter-names` handling already registered, and it honors standard Jackson
annotations when present. `HealthEndpoint.Health` (kept `@JsonCreator`/`@JsonProperty`) is the
in-repo witness: its existing `GET /health` integration test must stay green, satisfying SC-006 and
US3 with no contrived code.

**Risk & mitigation**: `Option` serialization inclusion (whether `None` emits `null` or is omitted)
could in principle affect a consumer. Mitigation: the `POST /greet` contract is pinned by
`contracts/greeting-api.md` and the existing endpoint tests; the reply fields are all required
(non-`Option`), so `None`-rendering only affects the *request* direction (deserialization), where
absent/null → `None` is the intended behavior. Verified by the offline suite plus the live smoke
test (FR-008).

## R5 — Do the mocked tests still exercise the same path?

**Decision**: Yes. Keep `TestModelProvider` with JSON strings via `JsonSupport.encodeToString(...)`.

**Rationale**: `TestKitSupport` starts the service (running `Bootstrap.onStartup`, hence
registering the module) before test methods run, so `JsonSupport.encodeToString` of an idiomatic
Scala `Result`/`Request` works in tests. The agent uses `responseAs` (feature 002, for the Gemini
tool-vs-JSON constraint), so the reply is parsed from model text through the shared `ObjectMapper`
and benefits directly. The mocked path and the converted types therefore stay consistent; the
live smoke test covers what mocks cannot (provider-specific serialization).

**Alternatives considered**: changing to `responseConformsTo` — explicitly out of scope (would
reintroduce the Gemini INVALID_ARGUMENT failure).
