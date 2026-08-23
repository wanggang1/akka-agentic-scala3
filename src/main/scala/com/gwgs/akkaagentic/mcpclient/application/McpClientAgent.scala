package com.gwgs.akkaagentic.mcpclient.application

import akka.javasdk.agent.{Agent, RemoteMcpTools}
import akka.javasdk.annotations.Component
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

object McpClientAgent:

  /** Hard fallback: the deployed name of THIS service — the Maven artifactId, which the SDK uses as
    * the service name in dev-mode (`akka.javasdk.dev-mode.service-name = ${?…project-artifact-id}`).
    * Used only when neither config key below yields a non-empty value.
    */
  val DefaultServiceName: String = "akka-agentic-scala3"

  /** Explicit override key (highest precedence). Set this in `application.conf` (or via its
    * `${?ENV}` substitution) when this service is deployed under a name other than the artifactId, so
    * the agent still points `fromService` at the right `/mcp`.
    */
  private val OverrideKey = "mcp-client.knowledge-service-name"

  /** The SDK's own resolved service name — the artifactId in dev-mode, or whatever a user set for
    * `akka.javasdk.dev-mode.service-name` in `application.conf`. Can be empty (e.g. under surefire
    * when `project-artifact-id` isn't set), which is why the resolution guards for blank.
    */
  private val SdkServiceNameKey = "akka.javasdk.dev-mode.service-name"

  /** Resolve the service name whose `/mcp` this agent consumes, config-overridable with an artifactId
    * fallback: explicit `mcp-client.knowledge-service-name` (if non-blank) → the SDK's resolved
    * `dev-mode.service-name` (if non-blank) → [[DefaultServiceName]] (the artifactId).
    */
  def resolveServiceName(config: Config): String =
    def nonBlankAt(key: String): Option[String] =
      Option.when(config.hasPath(key))(config.getString(key).trim).filter(_.nonEmpty)
    nonBlankAt(OverrideKey)
      .orElse(nonBlankAt(SdkServiceNameKey))
      .getOrElse(DefaultServiceName)

  /** A degraded reply used when the turn fails (e.g. the remote MCP server is unreachable). Keeps a
    * failed request a clean `200` with an honest message instead of a raw `500`, and — via the
    * accompanying `logger.warn` — surfaces the real cause (the cap-6 robustness pattern, FR-010).
    */
  val FailureReply: String =
    "I'm unable to answer right now because the knowledge service is unavailable."

  private val SystemMessage: String =
    """You are a documentation assistant for this project's knowledge base.
      |
      |You have a tool named `retrieve` that performs semantic search over the knowledge corpus and
      |returns the most relevant passages (each with a `source` label and the passage `text`). When a
      |question is about this project — its capabilities, its Akka/Scala interop findings, how things
      |work — CALL `retrieve` to ground your answer, then answer concisely using ONLY the returned
      |passages. Do not use outside knowledge and do not guess.
      |
      |If the retrieved passages do not contain enough information to answer, say plainly that you
      |don't know rather than inventing an answer. Keep answers to one or two sentences.""".stripMargin

/** Capability 10 — a request-based Agent that grounds its answers by calling a **remote MCP tool**.
  *
  * Unlike cap-8 (`DocsAgent`), which is handed passages the *endpoint* retrieved before the model ran
  * (pre-retrieval), this agent is given the `retrieve` tool of this service's own cap-9 MCP server
  * (`KnowledgeMcpEndpoint` at `/mcp`) and the **model decides** whether/when to call it — the
  * "retrieval-as-a-tool / agentic RAG" fork cap-8 flagged. This closes the loop entirely in-process:
  * agent → MCP client → our MCP server → cap-8's `KnowledgeStore`.
  *
  * '''Interop finding (cap-10 R1, bytecode-verified).''' Consuming a remote MCP server is Scala-clean:
  * `RemoteMcpTools.fromService/fromServer` is a URL-string/config builder with no Java method
  * references, so `.mcpTools(...)` has none of the Workflow/entity method-ref wall. The wall is a
  * `ComponentClient`-method-ref property, and the MCP client is not a `ComponentClient` — the same
  * reason `.tools()` and the Agent/AutonomousAgent/Task/DI clients are Scala-clean.
  *
  * Payload is a bare `String` in / `String` out (the cap-4/cap-8 shape) — no Java-shaped wire type
  * crosses the SDK's internal mapper; grounding comes from the tool, not a typed structured result.
  */
@Component(id = "mcp-client-agent")
class McpClientAgent(config: Config) extends Agent:
  import McpClientAgent.*

  private val logger = LoggerFactory.getLogger(getClass)

  /** The service whose `/mcp` server this agent consumes — this same service (the loop is closed
    * in-process). Config-overridable with an artifactId fallback (see [[resolveServiceName]]). */
  private val serviceName: String = resolveServiceName(config)

  /** Answer one question, grounding via the remote `retrieve` MCP tool at the model's discretion. A
    * failed turn degrades to [[FailureReply]] and logs the real cause (FR-010), rather than a 500.
    */
  def ask(question: String): Agent.Effect[String] =
    effects()
      .systemMessage(SystemMessage)
      // Scala-clean: a URL-string builder, no method reference (cap-10 R1). Points at THIS service's
      // own cap-9 /mcp server; the model calls its `retrieve` tool to ground the answer.
      //
      // `fromService(name)` is unambiguous here because it targets exactly ONE endpoint: it builds
      // `http://<name>/mcp` — the `/mcp` path is hardcoded in the factory (cap-10 R2b) — and this
      // service hosts a single @McpEndpoint, at that default `/mcp` path (cap-9). If a second MCP
      // server were ever added it would need a distinct path, and reaching it would require
      // `fromServer("http://<name>/other-path")` (fromService has no path parameter).
      .mcpTools(RemoteMcpTools.fromService(serviceName))
      .userMessage(question)
      .onFailure { error =>
        logger.warn("mcp-client-agent turn failed; degrading to failure reply", error)
        FailureReply
      }
      .thenReply()
