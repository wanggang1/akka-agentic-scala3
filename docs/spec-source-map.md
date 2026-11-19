# Spec → source map

Maps each `specs/<NNN-feature>/` directory to the capability it became, the README interop
note (§) and endpoint that document it, and the **source package / key files** that implement
it. Tests live in the mirror package under `src/test/`.

This is a convenience index, not a source of truth — the repository is. If a row disagrees with
the tree, the tree wins; fix the row.

## How the numbers line up

Three numbering schemes run in parallel, and they do **not** all match:

- **Spec dir `NNN`** — the SDD feature folder, in build order (`001`…`024`).
- **Capability `N`** — the README's narrative unit. **`cap = NNN − 2` for `NNN ≥ 004`.** Specs
  `001` (greeting baseline), `002` (structured output + tool) and `003` (Scala-native JSON) are
  the **foundation**: `001`+`002` are **capability 1**, and `003` is the wire-type plumbing
  (`DefaultScalaModule` in `Bootstrap`), documented as an interop note rather than a capability.
- **Interop note `§M`** — numbered `§1`–`§24` under README "Scala interop notes". `§1` (descriptor),
  `§2` (`dynamicCall`) and `§3` (wire types) are cross-cutting; from `§4` on, **`§ = cap + 2`**.

So, e.g., **spec `020-event-sourced-wallet` = capability 18 = README §20**, in package `wallet/`.

Two specs reference a *different* package than their own because of cross-capability edges —
called out inline below:
- `018-event-consumer` implements the **`feed/`** package; it only *consumes* cap-6's `a2a/`
  `TodoEntity`.
- `019-session-compaction` implements the **`compaction/`** package.

All source paths are under `src/main/{scala,java}/com/gwgs/akkaagentic/`. Tests are under
`src/test/...` in the same package (suffix `Test` for unit, `IntegrationTest` for runtime).

## The map

| Spec dir | Cap | README | Package | Key source files | Lang |
|---|---|---|---|---|---|
| `001-greeting-agent` | 1 | §1–§2, §3; *Cap 1* | `api/`, `application/`, `domain/` (root) | `api/GreetingEndpoint`, `api/HealthEndpoint`, `application/GreetingAgent`, `application/Bootstrap`, `domain/Greeting` | Scala |
| `002-agent-tools-structured` | 1 | *Cap 1* | root + `team/domain/` | `application/GreetingAgent` (structured output + `@FunctionTool`), `domain/Greeting`, `team/domain/TimeOfDay` | Scala + Java |
| `003-scala-native-json` | — | §3 | `application/` | `application/Bootstrap` (registers `DefaultScalaModule`) | Scala |
| `004-multi-agent-workflow` | 2 | §4; `POST /greetings` | `team/` | `team/application/GreetingWorkflow`, `ToneAgent`, `GreetingComposerAgent`, `team/api/GreetingTeamEndpoint`, `team/domain/Tone` | **Java** (workflow wall) |
| `005-autonomous-agent` | 3 | §5; `POST /help` | `assistant/` | `assistant/application/HelpDeskAgent`, `HelpDeskTasks`, `HelpAnswer`, `assistant/api/HelpDeskEndpoint`, `assistant/domain/{HelpQuestion,KnowledgeBase}` | Scala |
| `006-session-memory` | 4 | §6; `POST /chat/{sessionId}` | `chat/` | `chat/application/ChatAgent`, `chat/api/ChatEndpoint`, `chat/domain/ChatMessage` | Scala |
| `007-human-approval-gate` | 5 | §7; `POST /approvals` | `approvals/` | `approvals/application/{DraftAgent,PublishAgent,ApprovalTasks,ApprovalDecision,Draft,PublishedReply}`, `approvals/api/ApprovalEndpoint`, `approvals/domain/{ApprovalCase,ApprovalQuestion,TaskOutcome}` | Scala |
| `008-agent-to-agent` | 6 | §8; `POST /request/{username}` | `a2a/` | `a2a/application/{PersonalAssistantAgent,ForwardTool,NullSafeAiContentInterceptor}`, `a2a/api/PersonalAssistantEndpoint`, `a2a/domain/AssistantRequest`; **Java** `a2a/application/{TodoEntity,TodoTools}`, `a2a/domain/{Todo,TodoList}` | Scala + Java (entity quarantine) |
| `009-autonomous-delegation` | 7 | §9; `POST /activities` | `activities/` | `activities/application/{ActivityCoordinator,ActivitySpecialist,WeatherSpecialist,ActivityTasks,ActivitySuggestion}`, `activities/api/ActivityEndpoint`, `activities/domain/{SuggestionQuestion,WeatherData}` | Scala |
| `010-rag-grounded-qa` | 8 | §10; `POST /ask` | `docs/` (RAG subset) | `docs/application/{DocsAgent,KnowledgeStore}`, `docs/api/DocsEndpoint`, `docs/domain/{AskQuestion,KnowledgeCorpus,Passage}` | Scala |
| `011-mcp-knowledge-server` | 9 | §11; `/mcp` | `mcp/` | `mcp/api/KnowledgeMcpEndpoint` (reuses cap-8 `KnowledgeStore`) | Scala |
| `012-mcp-client` | 10 | §12; `POST /grounded-ask` | `mcpclient/` | `mcpclient/application/McpClientAgent`, `mcpclient/api/McpClientEndpoint` | Scala |
| `013-views-read-model` | 11 | §13; `GET /todo-summaries/...` | `todos/` | `todos/application/{TodoSummaryView,TodoSummaryEntry,TodoSummaryEntries}`, `todos/domain/TodoSummary`; **Java** `todos/api/TodoSummaryEndpoint` (ViewClient caller). Reads cap-6's `a2a` `TodoEntity` | Scala + Java (caller) |
| `014-agent-guardrails` | 12 | §14; governance on `POST /ask` | `docs/` (guardrail subset) | `docs/application/{AnswerLengthGuard,LinkedAnswerGuard}`, `docs/domain/{AnswerRules,GuardrailAudit}` | Scala |
| `015-llm-judge-evaluation` | 13 | §15; `POST /evaluate` | `eval/` | `eval/application/{AnswerEvaluator,DeclineJudge}`, `eval/api/EvaluationEndpoint`, `eval/domain/{EvaluationApplicability,ReferenceText}` | Scala |
| `016-streaming-responses` | 14 | §16; `POST /stream-chat/{sessionId}` | `streaming/` (stream subset) | `streaming/application/StreamingChatAgent`, `streaming/domain/StreamQuestion`; **Java** `streaming/api/{StreamingChatEndpoint,SseChatEvent}` (tokenStream caller) | Scala + Java (caller) |
| `017-timed-action` | 15 | §17; `POST`/`GET`/`DELETE /reminders` | `reminders/` | `reminders/application/{ReminderAction,ReminderStore,ReminderSettings,BoundedAttempts}`, `reminders/api/ReminderEndpoint`, `reminders/domain/{Reminder,Reminders,ReminderRequest,ReminderState,CancelOutcome}`; **Java** `reminders/api/ReminderSchedulingEndpoint` (scheduler) | Scala + Java (scheduler) |
| `018-event-consumer` | 16 | §18; `GET /todo-activity` + topic | **`feed/`** | `feed/application/{TodoActivityConsumer,ActivityStore,BoundedDelivery,FeedSettings}`, `feed/api/TodoActivityEndpoint`, `feed/domain/{ActivityFeed,TodoChange,TodoDiff,TodoSnapshot}`. Consumes cap-6 `a2a` `TodoEntity` | Scala |
| `019-session-compaction` | 17 | §19; `GET /compaction` | **`compaction/`** | `compaction/application/{CompactionAgent,Compactor,CompactionStore,CompactionSettings,ConversationSummary,SessionMemoryConsumer}`, `compaction/api/CompactionEndpoint`, `compaction/domain/{CompactionDecision,CompactionLedger,CompactionThreshold,HistoryLine,SummaryRequest}`; **Java** `compaction/application/SessionMemoryGateway` | Scala + Java (one class) |
| `020-event-sourced-wallet` | 18 | §20; `POST /wallets/...` | `wallet/` | `wallet/application/WalletEntity`, `wallet/domain/Wallet`; **Java** `wallet/api/WalletEndpoint`, `wallet/domain/WalletEvent` (event hierarchy + caller) | Scala + Java (events + caller) |
| `021-grpc-endpoint` | 19 | §21; gRPC `Greeter` | `grpc/` + `proto` | `grpc/api/GreeterGrpcEndpointImpl`, `src/main/proto/com/gwgs/akkaagentic/grpc/greeter.proto`; fronts cap-1's `GreetingAgent` via `dynamicCall` | Scala over Java-generated stubs |
| `022-sse-streaming` | 20 | §22; SSE framing | `streaming/` (SSE subset) | `streaming/domain/SseErrorReason`; **Java** `streaming/api/{SseChatEndpoint,SseChatEvent}` | Scala + Java (endpoint) |
| `023-usage-accurate-citations` | 21 | §23; `POST /cited-ask` | `docs/` (cited subset) | `docs/application/CitingDocsAgent`, `docs/api/CitedDocsEndpoint`, `docs/domain/RetrievedSources` | Scala |
| `024-streaming-grounded-citations` | 22 | §24; `POST /sse-cited-ask` | `docs/` (streaming cited subset) | `docs/application/{StreamingCitedDocsAgent,GroundedCitedPrep}`, `docs/domain/CitationMarkers`; **Java** `docs/api/{StreamingCitedDocsEndpoint,GroundedSseEvent}` | Scala + Java (endpoint + envelope) |

## Shared packages — which capability owns which file

Two packages host more than one capability. The split:

### `docs/` — capabilities 8, 12, 21, 22

| Capability | Files |
|---|---|
| 8 — RAG (`/ask`) | `application/DocsAgent`, `application/KnowledgeStore`, `api/DocsEndpoint`, `domain/{AskQuestion,KnowledgeCorpus,Passage}` |
| 12 — guardrails | `application/{AnswerLengthGuard,LinkedAnswerGuard}`, `domain/{AnswerRules,GuardrailAudit}` |
| 21 — usage-accurate citations (`/cited-ask`) | `application/CitingDocsAgent`, `api/CitedDocsEndpoint`, `domain/RetrievedSources` |
| 22 — streaming grounded citations (`/sse-cited-ask`) | `application/{StreamingCitedDocsAgent,GroundedCitedPrep}`, `domain/CitationMarkers`, **Java** `api/{StreamingCitedDocsEndpoint,GroundedSseEvent}` |

### `streaming/` — capabilities 14, 20

| Capability | Files |
|---|---|
| 14 — streamed chat (`/stream-chat`) | `application/StreamingChatAgent`, `domain/StreamQuestion`, **Java** `api/{StreamingChatEndpoint,SseChatEvent}` |
| 20 — SSE framing (`/sse-cited`…) | `domain/SseErrorReason`, **Java** `api/{SseChatEndpoint,SseChatEvent}` |

## `probe/` sub-packages

Several capabilities keep throwaway probes that proved an interop finding but are not part of the
shipped surface: `compaction/probe/`, `feed/probe/`, `reminders/probe/`, `streaming/probe/`. They
belong to the capability of their parent package and are excluded from the production surface
(see the `No*InProduction` / `NoJavaInProduction` quarantine tests).

## See also

- `ROADMAP.md` — "Where we are" and the A/B fork map.
- `README.md` "Scala interop notes" §1–§24 — the interop finding per capability (read by section).
- `FINDINGS.md` — the cross-capability findings ledger.
- `specs/<NNN>/{spec,plan,research,tasks}.md` — full design detail per feature.
