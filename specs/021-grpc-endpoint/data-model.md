# Data Model: gRPC endpoint fronting an agent

No persistent state and no new domain types. The capability adds two **protobuf wire messages** (generated
Java) and reuses capability 1's existing request/reply types. The boundary conversion is the only new logic.

## Wire messages (protobuf — generated Java, Java-shaped)

Defined in `contracts/greeter.proto` → copied to `src/main/proto/com/gwgs/akkaagentic/grpc/greeter.proto`.
Generated into `target/generated-sources/akka-grpc-java` as `com.gwgs.akkaagentic.grpc.proto.*`.

### GreetRequest

| Field      | proto type | tag | Notes                                                                 |
|------------|-----------|-----|-----------------------------------------------------------------------|
| `user`     | `string`  | 1   | Required by the endpoint's validation; empty/blank → `INVALID_ARGUMENT`. |
| `text`     | `string`  | 2   | Required by the endpoint's validation; empty/blank → `INVALID_ARGUMENT`. |
| `timezone` | `string`  | 3   | Optional. proto3 scalar default is `""` (never null). Empty → agent uses UTC. |

proto3 scalars are never null in Java; absence is the empty string. So the endpoint maps `""` → "absent"
when bridging to the agent's `Option`/nullable timezone — there is no null to guard, unlike JSON.

### GreetReply

| Field         | proto type | tag | Notes                                              |
|---------------|-----------|-----|----------------------------------------------------|
| `greeting`    | `string`  | 1   | From `GreetingAgent.Result.greeting`.              |
| `tone`        | `string`  | 2   | From `GreetingAgent.Result.tone`.                  |
| `time_of_day` | `string`  | 3   | From `GreetingAgent.Result.timeOfDay` (proto snake_case → generated `getTimeOfDay()`). |

## Reused types (capability 1 — unchanged)

- **`com.gwgs.akkaagentic.domain.GreetingRequest`** — the pure domain request with `.validate:
  Either[String, ValidGreeting]`. Reused for boundary validation (parse-don't-validate): the gRPC endpoint
  builds a `GreetingRequest` from the proto fields and rejects a `Left` with `INVALID_ARGUMENT`.
- **`com.gwgs.akkaagentic.application.GreetingAgent.Request`** — Java-shaped (nullable `String timezone`);
  sent to the agent via `dynamicCall`. `""` timezone bridges to `null` (`.orNull`-style), matching the HTTP
  endpoint.
- **`com.gwgs.akkaagentic.application.GreetingAgent.Result`** — the agent's structured reply; mapped to
  `GreetReply` by the endpoint's private `toApi`.

## Conversions (the only new logic)

```
GreetRequest (proto)  --validate/toDomain-->  GreetingRequest.validate
                                                 Left(msg)  -> throw GrpcServiceException(INVALID_ARGUMENT, msg)
                                                 Right(valid) -> GreetingAgent.Request(valid.user, valid.text, tzOrNull)
GreetingAgent.Result  --toApi-->               GreetReply(greeting, tone, timeOfDay)
```

No state transitions, no entities, no storage.
