package com.agenttrail.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AgentTrailClient}. The HTTP transport is stubbed by
 * overriding {@link AgentTrailClient#get} (no sockets are opened).
 */
class AgentTrailClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A recorded GET: path plus query params. */
    record Request(String path, Map<String, List<String>> params) {}

    /** Client whose transport returns canned JSON and records requests. */
    static class FakeClient extends AgentTrailClient {
        final Deque<Object> responses = new ArrayDeque<>();
        final Deque<Request> requests = new ArrayDeque<>();

        FakeClient() {
            super("http://127.0.0.1:9", "test-key");
        }

        @Override
        protected JsonNode get(String path, Map<String, List<String>> params) throws IOException {
            requests.add(new Request(path, params));
            Object next = responses.poll();
            if (next instanceof ApiException e) throw e;
            if (next instanceof String s) {
                try {
                    return MAPPER.readTree(s);
                } catch (IOException io) {
                    throw new AssertionError("bad stub json", io);
                }
            }
            throw new AssertionError("no stubbed response for " + path);
        }
    }

    private FakeClient client;

    @BeforeEach
    void setUp() {
        client = new FakeClient();
    }

    private static final String SESSION_PAGE = """
            {"content":[{"id":"s1","agentType":"claude-code","model":"opus","status":"failed",
            "startedAt":"2026-10-16T18:00:00Z","summary":"fix npe","cwd":"/tmp/x",
            "metadata":{"host":"mbp"},
            "stats":{"eventCount":42,"errorCount":3,"toolCallCount":25,"costUsd":1.8421}}],
            "page":0,"size":20,"totalElements":1,"totalPages":1}""";

    private static final String EVENTS_PAGE = """
            {"content":[
            {"id":"e1","sessionId":"s1","seq":1,"ts":"2026-10-16T18:00:01Z","type":"tool_call",
             "actor":"agent","name":"Bash","summary":"ran `npm test` (exit 1, 4.2s)",
             "durationMs":4200,"costUsd":0.01,"tags":["test","failure"]},
            {"id":"e2","sessionId":"s1","seq":2,"ts":"2026-10-16T18:00:05Z","type":"error",
             "actor":"agent","name":"","summary":"NullPointerException in Checkout",
             "costUsd":0,"tags":[]}],
            "page":0,"size":20,"totalElements":2,"totalPages":1}""";

    @Test
    void listSessionsSendsFiltersAndCompactsFields() throws Exception {
        client.responses.add(SESSION_PAGE);

        String out = client.listSessions("claude-code", "failed", null, null, null, 0, 20);
        JsonNode root = MAPPER.readTree(out);

        assertThat(root.path("totalElements").asLong()).isEqualTo(1);
        JsonNode s = root.path("sessions").get(0);
        assertThat(s.path("id").asText()).isEqualTo("s1");
        assertThat(s.path("status").asText()).isEqualTo("failed");
        assertThat(s.path("stats").path("errorCount").asLong()).isEqualTo(3);
        // compacted: verbose fields are trimmed for the context window
        assertThat(s.has("cwd")).isFalse();
        assertThat(s.has("metadata")).isFalse();

        Request req = client.requests.poll();
        assertThat(req.path()).isEqualTo("/api/v1/sessions");
        assertThat(req.params().get("agentType")).containsExactly("claude-code");
        assertThat(req.params().get("status")).containsExactly("failed");
    }

    @Test
    void getSessionEventsSendsRepeatedTypeParams() throws Exception {
        client.responses.add(EVENTS_PAGE);

        String out = client.getSessionEvents("s1", List.of("tool_call", "error"), null, null, 0, 20);
        JsonNode root = MAPPER.readTree(out);

        assertThat(root.path("sessionId").asText()).isEqualTo("s1");
        assertThat(root.path("events")).hasSize(2);
        JsonNode e = root.path("events").get(0);
        assertThat(e.path("seq").asInt()).isEqualTo(1);
        assertThat(e.path("summary").asText()).contains("npm test");
        assertThat(e.path("tags")).hasSize(2);

        Request req = client.requests.poll();
        assertThat(req.path()).isEqualTo("/api/v1/sessions/s1/events");
        assertThat(req.params().get("type")).containsExactly("tool_call", "error");
    }

    @Test
    void apiErrorsBecomeApiException() {
        client.responses.add(new AgentTrailClient.ApiException(404, "{\"error\":\"not_found\"}"));
        assertThatThrownBy(() -> client.getSessionEvents("nope", List.of(), null, null, 0, 20))
                .isInstanceOf(AgentTrailClient.ApiException.class)
                .satisfies(ex -> assertThat(((AgentTrailClient.ApiException) ex).status).isEqualTo(404));
    }

    @Test
    void unauthorizedBecomesApiException() {
        client.responses.add(new AgentTrailClient.ApiException(401, "{\"error\":\"unauthorized\"}"));
        assertThatThrownBy(() -> client.listSessions(null, null, null, null, null, 0, 20))
                .isInstanceOf(AgentTrailClient.ApiException.class)
                .satisfies(ex -> assertThat(((AgentTrailClient.ApiException) ex).status).isEqualTo(401));
    }

    @Test
    void searchEventsFansOutOverSessionsAndMerges() throws Exception {
        client.responses.add(SESSION_PAGE);
        client.responses.add(EVENTS_PAGE);

        String out = client.searchEvents("npm", null, null, null, null, 20, 50);
        JsonNode root = MAPPER.readTree(out);

        assertThat(root.path("query").asText()).isEqualTo("npm");
        assertThat(root.path("sessionsScanned").asInt()).isEqualTo(1);
        assertThat(root.path("events")).hasSize(2);
        assertThat(root.path("events").get(0).path("sessionId").asText()).isEqualTo("s1");
        // sorted by ts ascending
        assertThat(root.path("events").get(0).path("seq").asInt()).isEqualTo(1);

        assertThat(client.requests.poll().path()).isEqualTo("/api/v1/sessions");
        Request eventsReq = client.requests.poll();
        assertThat(eventsReq.path()).isEqualTo("/api/v1/sessions/s1/events");
        assertThat(eventsReq.params().get("q")).containsExactly("npm");
    }

    @Test
    void costSummaryMergesOverviewAndTopSessions() throws Exception {
        client.responses.add("""
                {"sessions":12,"events":1830,"toolCalls":940,"errors":37,
                 "totalCostUsd":18.42,"totalTokensIn":1200000,"totalTokensOut":340000,
                 "costByDay":[{"day":"2026-10-16","costUsd":4.2}]}""");
        client.responses.add(SESSION_PAGE);

        String out = client.costSummary(null, null, null, 5);
        JsonNode root = MAPPER.readTree(out);

        assertThat(root.path("totalCostUsd").asDouble()).isEqualTo(18.42);
        assertThat(root.path("totalTokensIn").asLong()).isEqualTo(1200000);
        assertThat(root.path("costByDay")).hasSize(1);
        assertThat(root.path("topSessionsByCost")).hasSize(1);
        assertThat(root.path("topSessionsByCost").get(0).path("costUsd").asDouble())
                .isEqualTo(1.8421);

        assertThat(client.requests.poll().path()).isEqualTo("/api/v1/stats/overview");
    }

    @Test
    void envFactoryUsesDefaultsWhenUnset() {
        assertThat(AgentTrailClient.fromEnv()).isNotNull();
    }
}
