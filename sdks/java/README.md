# AgentTrail Java SDK

Dependency-free Java client for the [AgentTrail v1 API](../../docs/API_CONTRACT.md)
(`com.agenttrail:agenttrail-java:0.1.0`). Requires Java 17+.

## Zero dependencies

The SDK's only runtime requirements are the JDK itself:

- HTTP via `java.net.http.HttpClient`
- JSON via the small hand-rolled `com.agenttrail.Json` serializer
  (maps, lists, arrays, strings, numbers, booleans, null — with proper string escaping)

No Jackson, no other libraries. JUnit 5 is used for tests only (`test` scope).

## Build & test

```bash
export PATH="$HOME/.maven/bin:$PATH"   # Maven 3.9.x lives in ~/.maven
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -B test
```

## Usage

```java
import com.agenttrail.AgentTrailClient;
import com.agenttrail.TimedTool;

// Picks up AGENTTRAIL_URL (default http://localhost:8080)
// and AGENTTRAIL_API_KEY (default dev-key) from the environment.
try (AgentTrailClient client = new AgentTrailClient()) {
    client.startSession("custom", "debug checkout flake");

    client.message("user", "The checkout test is flaky");

    try (TimedTool t = client.tool("Bash", "ran unit tests")) {
        runTests();
    }

    client.handoff("reviewer", "handing off the fixed checkout flow");
    client.fileChange("src/Checkout.java", "edit", "fixed NPE in total()");
    client.cost(1200, 800, 0.012);

    client.endSession("completed"); // posts session_end, flushes, PATCHes status, closes
}
```

Recording a failing tool call (the `tool_call` is tagged `failure`
and an `error` event is posted too):

```java
try (TimedTool t = client.tool("Bash")) {
    try {
        riskyWork();
    } catch (RuntimeException e) {
        t.failed(e);   // mark before the resource closes
        throw e;
    }
}
```

(`AutoCloseable.close()` cannot see the in-flight exception, hence the
explicit `failed(...)` marker — unlike the Python SDK's context manager.)

### Builder

```java
AgentTrailClient client = AgentTrailClient.builder()
        .serverUrl("http://localhost:8080")
        .apiKey(System.getenv("AGENTTRAIL_API_KEY"))
        .batchSize(50)              // flush when 50 events accumulate
        .flushIntervalSeconds(5)    // ...or every 5 seconds, via a daemon thread
        .maxRetries(3)              // exponential backoff, then AgentTrailException
        .build();
```

`new AgentTrailClient(serverUrl, apiKey)` is a shorthand for the common case.

## Behavior notes

- **Batching**: events are queued and flushed by a background daemon thread
  every N events or M seconds. Multi-event flushes use
  `POST /api/v1/sessions/{id}/events:batch`; a lone event uses
  `POST /api/v1/sessions/{id}/events`. `flush()` sends everything queued now.
- **Idempotent retries**: every event carries a client-generated UUID `id`,
  so retried POSTs are deduplicated server-side.
- **Retries**: failed requests are retried with exponential backoff
  (`min(2^attempt * 0.5s, 8s)`); `AgentTrailException` (a `RuntimeException`)
  is thrown only after retries are exhausted. Background flushes are
  best-effort — inspect `getLastFlushError()`; explicit `flush()` raises.
- **Lifecycle**: `endSession(status)` posts `session_end`, flushes, PATCHes
  the session status, and shuts down the background thread. `close()` flushes
  and stops the thread; the client is also `AutoCloseable`.
- Event summaries are truncated to 280 chars per the API contract.
