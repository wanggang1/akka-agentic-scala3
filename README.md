# Akka Agentic Scala3

A baseline **Scala 3** agentic service built on the **Akka Java SDK**. It exposes a greeting
agent that accepts a typed JSON payload (`user`, `text`) over HTTP and returns a personalized,
LLM-composed greeting using the Akka Agentic Platform Effects API.

The Scala 3 sources subclass the Java SDK component types directly (`Agent`, HTTP endpoints) and
are compiled alongside the SDK via the `scala-maven-plugin`. See
[Development Process](https://doc.akka.io/concepts/development-process.html) and
[Developing services](https://doc.akka.io/sdk/index.html) for the underlying Akka concepts.

## Project layout

```text
src/main/scala/com/gwgs/akkaagentic/domain/        # GreetingRequest / GreetingResponse (+ validation)
src/main/scala/com/gwgs/akkaagentic/application/    # GreetingAgent
src/main/scala/com/gwgs/akkaagentic/api/            # GreetingEndpoint (POST /greet)
src/main/resources/application.conf                 # default model-provider config
src/test/scala/com/gwgs/akkaagentic/...             # tests (TestModelProvider, no live model)
```

- **groupId**: `com.gwgs` · **base package**: `com.gwgs.akkaagentic` · **service**: `akka-agentic-scala3`

## Build

```shell
mvn compile
```

The build compiles Scala 3 via the `scala-maven-plugin` configured in `pom.xml`.

## Test

```shell
mvn verify
```

Tests register a `TestModelProvider`, so **no API key or network is required** — results are
deterministic.

## Run locally

This service uses a Google AI Gemini model (`gemini-2.5-flash`), which needs an API key at
runtime. The key is read from the `GOOGLE_AI_GEMINI_API_KEY` environment variable (see
`application.conf`). Copy `.env.example` to `.env` (git-ignored), set your key there, then load it
into the environment before running — the JVM does **not** read `.env` automatically:

```shell
cp .env.example .env          # then edit .env and set your key
set -a && source .env && set +a && mvn compile exec:java
```

The service listens on `http://localhost:9000`. Example request:

```shell
curl -i -X POST http://localhost:9000/greet \
  -H "Content-Type: application/json" \
  -d '{"user":"Ada","text":"hello there"}'
```

You can use the [Akka Console](https://console.akka.io) to create a project and see the status of
your service.

## Deploy

Build the container image:

```shell
mvn clean install -DskipTests
```

Install the `akka` CLI as documented in
[Install Akka CLI](https://doc.akka.io/reference/cli/index.html), then deploy using the image tag
from the `mvn install` above:

```shell
akka service deploy akka-agentic-scala3 akka-agentic-scala3:tag-name --push
```

Refer to [Deploy and manage services](https://doc.akka.io/operations/services/deploy-service.html)
for more information.
