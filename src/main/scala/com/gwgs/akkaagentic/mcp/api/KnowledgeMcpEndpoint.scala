package com.gwgs.akkaagentic.mcp.api

import akka.javasdk.JsonSupport
import akka.javasdk.annotations.{Acl, Description}
import akka.javasdk.annotations.mcp.{McpEndpoint, McpResource, McpTool}
import com.gwgs.akkaagentic.docs.application.KnowledgeStore
import com.gwgs.akkaagentic.docs.domain.{AskQuestion, KnowledgeCorpus}

/** Capability 9 — the Model Context Protocol surface over capability 8's semantic retrieval.
  *
  * An `@McpEndpoint` is an *endpoint*, not a `@Component` (like `@HttpEndpoint`): a remote MCP client
  * calls its `@McpTool` methods over JSON-RPC at `/mcp`, and the SDK dispatches to them **reflectively**
  * from the request — there is no `ComponentClient` method reference for us to author, so none of the
  * Workflow/entity method-ref wall applies. This is why the whole capability is idiomatic Scala (research
  * R1). The retrieval call below is a plain method call on the injected [[KnowledgeStore]] (provided by
  * `Bootstrap`'s `DependencyProvider`, cap-8 R5) — no component client at all.
  *
  * Two Scala-on-MCP findings proven while building this (see specs/011 research R2, README §11):
  *   1. **`@McpTool` methods MUST return `String`** — the SDK rejects any other return type at startup
  *      (*"MCP tool method must return String"*). So the result is rendered to a JSON string here; a typed
  *      result record is not an option (unlike an HTTP endpoint or an autonomous-agent task).
  *   2. **Bare tool parameters work in Scala** — the SDK reflects each parameter into a top-level JSON
  *      Schema property *by its name*, and scalac emits parameter names in this build (the mixed-build
  *      `-parameters` flag, §4). So `retrieve(question)` needs no wrapper record and no manual
  *      `inputSchema`.
  *
  * '''SDK-3.6.0 limitation — no tunable `maxResults` (revisit on upgrade).''' The tool intentionally
  * exposes only `question` and returns a fixed top-K (3) — exactly mirroring cap-8's `DocsEndpoint`. A
  * tunable `maxResults` was designed (spec SC-006) but cannot be expressed cleanly on this SDK: the
  * documented optional-parameter mechanism, a `java.util.Optional[T]` bare param (mcp-endpoints.html.md),
  * throws `"class java.util.Optional cannot be cast to class java.lang.Integer"` on any *supplied* value
  * in 3.6.0 (the cast is Optional→element, so it fails for every element type — proven in T006); a plain
  * `Integer` binds supplied values fine but the SDK then marks it *required*, and a manual `inputSchema`
  * on bare params breaks argument binding entirely (`"argument type mismatch"`). All three dead ends are
  * an SDK bug, not a Scala-interop wall. '''TODO: when the SDK is upgraded past 3.6.0, re-add the optional
  * `maxResults` param (preferred shape: `Optional[Integer]`) and restore the clamping tests''' — tracked
  * with the project's other 3.6.0-version-limited items. See specs/011 research R2 and README §11.
  */
object KnowledgeMcpEndpoint:

  /** Number of passages the tool returns — a fixed top-K, matching cap-8's `DocsEndpoint.TopK`. */
  private val TopK = 3

  /** One rendered search hit. A plain Scala case class serialized by [[JsonSupport]]'s (Scala-aware)
    * mapper into the tool's text result. Not a wire *component*; just the shape of the JSON the tool
    * returns — `KnowledgeStore.Retrieved` never leaves the endpoint (API isolation). */
  private final case class RetrievedPassage(source: String, score: Double, text: String)

@McpEndpoint(serverName = "akka-agentic-knowledge-mcp", serverVersion = "0.1.0")
@Acl(allow = Array(new Acl.Matcher(principal = Acl.Principal.INTERNET)))
class KnowledgeMcpEndpoint(knowledgeStore: KnowledgeStore):
  import KnowledgeMcpEndpoint.*

  /** Semantic search over the knowledge corpus. Validates the question first — a blank question is a
    * tool error with **no retrieval** (FR-005), surfaced to the client as an MCP `isError` result — then
    * retrieves the fixed top-K passages and returns them as a JSON array of `{source, score, text}`,
    * ordered by descending similarity.
    *
    * `question` is the sole (required) parameter — the SDK rejects a missing one before we run. On a
    * blank question we `throw`: an MCP tool has no `Effect`/`Either` return channel (it must return
    * `String`), so throwing is the SDK's error mechanism — the runtime turns it into a well-formed
    * `{isError:true}` tool result. The domain stays pure: `AskQuestion.validate` returns `Either`, and
    * this adapter translates its `Left` to that error, exactly as `DocsEndpoint` translates the same
    * `Left` to `HttpResponses.badRequest`.
    */
  @McpTool(
    name = "retrieve",
    description =
      "Semantic search over the knowledge corpus; returns the most similar passages with their " +
        "source labels and similarity scores."
  )
  def retrieve(
      @Description("The natural-language question to search for.") question: String
  ): String =
    AskQuestion.validate(Option(question)) match
      case Left(message) =>
        // Throwing is the framework's error channel for an MCP tool: a @McpTool must return String
        // (the SDK rejects any other return type at startup) — there is no Effect/Either/typed-error
        // return like an HTTP endpoint's HttpResponse or an entity's effects().error(). The runtime
        // catches this exception and turns it into a well-formed MCP tool error
        // ({content:[{type:"text",text:<message>}], isError:true}) that the calling model sees — NOT a
        // protocol crash. So the domain stays pure (AskQuestion.validate returns Either) and this thin
        // adapter just translates the Left into the SDK's exception-based error mechanism.
        throw new IllegalArgumentException(message)
      case Right(valid) =>
        val passages = knowledgeStore
          .retrieve(valid.question, TopK)
          .map(r => RetrievedPassage(r.source, r.score, r.text))
        JsonSupport.encodeToString(passages)

  /** A static MCP **resource** exposing the corpus's source labels — the discoverable "table of contents"
    * a client can `resources/read` to see what the `retrieve` tool can ground on. A static resource is a
    * public **zero-parameter** method returning `String` (raw text): it has **no input schema and no
    * param-name/mapper concern** (research R5), so it carries none of the tool's `maxResults` friction —
    * a strictly simpler MCP surface than the tool. The body renders the labels to a JSON array with the
    * Scala-aware [[JsonSupport]] mapper (a plain `List[String]`), matching the declared `application/json`
    * mime type. Reads `KnowledgeCorpus` directly (pure data) — no retrieval, no injected store needed. */
  @McpResource(
    uri = "knowledge://corpus/sources",
    name = "Knowledge corpus sources",
    description = "The source labels of every passage in the knowledge corpus.",
    mimeType = "application/json"
  )
  def corpusSources(): String =
    JsonSupport.encodeToString(KnowledgeCorpus.passages.map(_.source))
