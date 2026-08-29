# Phase 1 Contract: To-do Read Model HTTP API (cap-11)

**Feature**: `013-views-read-model` | **Date**: 2026-08-28
**Endpoint class**: `com.gwgs.akkaagentic.todos.api.TodoSummaryEndpoint` (**Java** — research R1)
**Base path**: `/todo-summaries` · **ACL**: `@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))`

**Read-only surface (FR-008).** Only `GET`. There is no POST/PUT/DELETE here and none may be added —
to-dos are still changed exclusively through capability 6's assistant.

Response types are **defined by the endpoint** (FR-010); the view row is never returned directly.

```java
public record TodoSummaryResponse(String username, int total, int open, int completed) {}
public record TodoSummariesResponse(List<TodoSummaryResponse> summaries) {}
```

---

## 1. `GET /todo-summaries/by-user/{username}`

One user's to-do standing.

| Case | Status | Body |
|---|---|---|
| Row exists | `200 OK` | `TodoSummaryResponse` |
| Well-formed username, no row | `404 Not Found` | *(none)* — distinct from `400` (FR-004) |
| Blank / whitespace-only username | `400 Bad Request` | `username must not be blank` — **no query runs** (FR-007) |

```shell
curl -s http://localhost:9000/todo-summaries/by-user/alice
# {"username":"alice","total":3,"open":2,"completed":1}

curl -i -s http://localhost:9000/todo-summaries/by-user/nobody
# 404 Not Found

curl -i -s http://localhost:9000/todo-summaries/by-user/%20%20
# 400 Bad Request — username must not be blank
```

A user who recorded items and then deleted them all returns `200` with all counts zero — **not** `404`.
`404` means "no record at all". (spec edge case; SC-002)

---

## 2. `GET /todo-summaries/with-open-work`

Every user holding at least one open item.

| Case | Status | Body |
|---|---|---|
| One or more qualify | `200 OK` | `TodoSummariesResponse` |
| None qualify | `200 OK` | `{"summaries":[]}` — **success, not `404`** (FR-006, SC-004) |

```shell
curl -s http://localhost:9000/todo-summaries/with-open-work
# {"summaries":[{"username":"alice","total":3,"open":2,"completed":1},
#               {"username":"bob","total":1,"open":1,"completed":0}]}
```

Users whose every item is completed are absent. Result order is unspecified — no `ORDER BY` is
declared (out of scope), so tests must compare order-insensitively.

---

## Path-shape note

`by-user/{username}` and `with-open-work` are deliberately **disjoint literal prefixes** rather than
`/{username}` + a sibling literal, so no route can be ambiguous between a path parameter and a static
segment. It also matches the project's existing `…/by-*` convention from AGENTS.md.

## Eventual consistency

Both endpoints read a projection (FR-009). A read taken immediately after a to-do changes may briefly
return the previous counts; it converges. This is stated in the README rather than hidden behind a
synchronous read, and every test asserts through `Awaitility`.

## Out of contract

No write operations · no per-item listing · no sorting, paging, or limits · no query parameters ·
no model involvement on any path (FR-011).
