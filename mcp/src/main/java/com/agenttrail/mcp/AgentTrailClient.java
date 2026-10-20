package com.agenttrail.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client over the AgentTrail v1 API (see ../docs/API_CONTRACT.md).
 *
 * <p>Every method returns a compact JSON string suitable for an LLM context window:
 * fields that add tokens without aiding debugging (raw ids of parents, verbose
 * metadata) are trimmed. Auth is via the {@code X-API-Key} header.
 */
public class AgentTrailClient {

    /** Thrown when the AgentTrail API answers with a non-2xx status. */
    public static final class ApiException extends RuntimeException {
        public final int status;
        public ApiException(int status, String body) {
            super("AgentTrail API returned " + status + ": " + body);
            this.status = status;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http;

    public AgentTrailClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /** Convenience factory reading AGENTTRAIL_BASE_URL / AGENTTRAIL_API_KEY from the environment. */
    public static AgentTrailClient fromEnv() {
        String base = System.getenv().getOrDefault("AGENTTRAIL_BASE_URL", "http://localhost:8080");
        String key = System.getenv().getOrDefault("AGENTTRAIL_API_KEY", "dev-key");
        return new AgentTrailClient(base, key);
    }

    // ------------------------------------------------------------------ tools

    /** List sessions with the API's filters; returns compact session objects plus a page envelope. */
    public String listSessions(String agentType, String status, String q,
                               String from, String to, int page, int size) throws IOException {
        Map<String, List<String>> params = new LinkedHashMap<>();
        put(params, "agentType", agentType);
        put(params, "status", status);
        put(params, "q", q);
        put(params, "from", from);
        put(params, "to", to);
        params.put("page", List.of(String.valueOf(page)));
        params.put("size", List.of(String.valueOf(Math.min(size, 50))));
        JsonNode env = get("/api/v1/sessions", params);

        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode s : env.path("content")) out.add(compactSession(s));
        ObjectNode result = MAPPER.createObjectNode();
        result.set("sessions", out);
        result.put("page", env.path("page").asInt(0));
        result.put("totalElements", env.path("totalElements").asLong(0));
        result.put("totalPages", env.path("totalPages").asInt(0));
        return MAPPER.writeValueAsString(result);
    }

    /** Events of one session, ordered by seq; compact per-event objects plus a page envelope. */
    public String getSessionEvents(String sessionId, List<String> types, String actor,
                                   String q, int page, int size) throws IOException {
        Map<String, List<String>> params = new LinkedHashMap<>();
        if (types != null && !types.isEmpty()) params.put("type", new ArrayList<>(types));
        put(params, "actor", actor);
        put(params, "q", q);
        params.put("page", List.of(String.valueOf(page)));
        params.put("size", List.of(String.valueOf(Math.min(size, 100))));
        JsonNode env = get("/api/v1/sessions/" + sessionId + "/events", params);

        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode e : env.path("content")) out.add(compactEvent(e));
        ObjectNode result = MAPPER.createObjectNode();
        result.put("sessionId", sessionId);
        result.set("events", out);
        result.put("page", env.path("page").asInt(0));
        result.put("totalElements", env.path("totalElements").asLong(0));
        result.put("totalPages", env.path("totalPages").asInt(0));
        return MAPPER.writeValueAsString(result);
    }

    /**
     * Free-text search over event summaries/names across sessions.
     *
     * <p>The v1 API has no cross-session event-search endpoint, so this fans out:
     * list candidate sessions (bounded), then query each session's events with {@code q}.
     * Results are merged and sorted by event timestamp. Bounded by {@code maxSessions}
     * sessions and {@code limit} events to keep context windows sane.
     */
    public String searchEvents(String q, String agentType, String status,
                               String from, String to, int maxSessions, int limit) throws IOException {
        maxSessions = Math.min(Math.max(maxSessions, 1), 50);
        limit = Math.min(Math.max(limit, 1), 200);

        Map<String, List<String>> params = new LinkedHashMap<>();
        put(params, "agentType", agentType);
        put(params, "status", status);
        put(params, "from", from);
        put(params, "to", to);
        params.put("page", List.of("0"));
        params.put("size", List.of(String.valueOf(maxSessions)));
        JsonNode env = get("/api/v1/sessions", params);

        List<JsonNode> hits = new ArrayList<>();
        for (JsonNode s : env.path("content")) {
            String sessionId = s.path("id").asText();
            Map<String, List<String>> ep = new LinkedHashMap<>();
            ep.put("q", List.of(q));
            ep.put("page", List.of("0"));
            ep.put("size", List.of("100"));
            JsonNode eEnv;
            try {
                eEnv = get("/api/v1/sessions/" + sessionId + "/events", ep);
            } catch (ApiException notFound) {
                continue; // session vanished between listing and querying
            }
            for (JsonNode e : eEnv.path("content")) {
                ObjectNode ce = compactEvent(e);
                ce.put("sessionId", sessionId);
                hits.add(ce);
            }
        }
        hits.sort((a, b) -> a.path("ts").asText("").compareTo(b.path("ts").asText("")));
        ArrayNode out = MAPPER.createArrayNode();
        hits.stream().limit(limit).forEach(out::add);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("query", q);
        result.put("sessionsScanned", env.path("content").size());
        result.set("events", out);
        result.put("truncated", hits.size() > limit);
        return MAPPER.writeValueAsString(result);
    }

    /** Cost rollup from /stats/overview plus the top-N most expensive sessions. */
    public String costSummary(String from, String to, String agentType, int topSessions) throws IOException {
        topSessions = Math.min(Math.max(topSessions, 1), 20);
        Map<String, List<String>> params = new LinkedHashMap<>();
        put(params, "from", from);
        put(params, "to", to);
        put(params, "agentType", agentType);
        JsonNode overview = get("/api/v1/stats/overview", params);

        Map<String, List<String>> sp = new LinkedHashMap<>();
        sp.put("page", List.of("0"));
        sp.put("size", List.of("200"));
        put(sp, "from", from);
        put(sp, "to", to);
        put(sp, "agentType", agentType);
        JsonNode sessions = get("/api/v1/sessions", sp);

        List<JsonNode> byCost = new ArrayList<>();
        for (JsonNode s : sessions.path("content")) byCost.add(s);
        byCost.sort((a, b) -> Double.compare(
                b.path("stats").path("costUsd").asDouble(0),
                a.path("stats").path("costUsd").asDouble(0)));

        ArrayNode top = MAPPER.createArrayNode();
        byCost.stream().limit(topSessions).forEach(s -> {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("sessionId", s.path("id").asText());
            o.put("summary", s.path("summary").asText(""));
            o.put("agentType", s.path("agentType").asText(""));
            o.put("costUsd", s.path("stats").path("costUsd").asDouble(0));
            o.put("eventCount", s.path("stats").path("eventCount").asLong(0));
            top.add(o);
        });

        ObjectNode result = MAPPER.createObjectNode();
        result.put("totalCostUsd", overview.path("totalCostUsd").asDouble(0));
        result.put("totalTokensIn", overview.path("totalTokensIn").asLong(0));
        result.put("totalTokensOut", overview.path("totalTokensOut").asLong(0));
        result.put("sessions", overview.path("sessions").asLong(0));
        result.set("costByDay", overview.path("costByDay"));
        result.set("topSessionsByCost", top);
        return MAPPER.writeValueAsString(result);
    }

    // ------------------------------------------------------------------ http

    /**
     * Executes a GET against the API. Protected so tests can stub the transport
     * without opening sockets.
     */
    protected JsonNode get(String path, Map<String, List<String>> params) throws IOException {
        String url = baseUrl + path + encode(params);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("X-API-Key", apiKey)
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (resp.statusCode() / 100 != 2) throw new ApiException(resp.statusCode(), resp.body());
        return MAPPER.readTree(resp.body());
    }

    private static String encode(Map<String, List<String>> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : params.entrySet()) {
            for (String v : e.getValue()) {
                if (v == null || v.isBlank()) continue;
                sb.append(sb.length() == 0 ? '?' : '&')
                  .append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                  .append('=')
                  .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    private static void put(Map<String, List<String>> params, String key, String value) {
        if (value != null && !value.isBlank()) params.put(key, List.of(value));
    }

    // ------------------------------------------------------------------ shape

    /** Trim a Session to the fields an assistant needs to pick a debugging target. */
    static ObjectNode compactSession(JsonNode s) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("id", s.path("id").asText());
        o.put("agentType", s.path("agentType").asText(""));
        o.put("model", s.path("model").asText(""));
        o.put("status", s.path("status").asText(""));
        o.put("startedAt", s.path("startedAt").asText(""));
        o.put("summary", s.path("summary").asText(""));
        JsonNode stats = s.path("stats");
        ObjectNode st = MAPPER.createObjectNode();
        st.put("eventCount", stats.path("eventCount").asLong(0));
        st.put("errorCount", stats.path("errorCount").asLong(0));
        st.put("toolCallCount", stats.path("toolCallCount").asLong(0));
        st.put("costUsd", stats.path("costUsd").asDouble(0));
        o.set("stats", st);
        return o;
    }

    /** Trim an Event to the fields that make a timeline readable. */
    static ObjectNode compactEvent(JsonNode e) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("seq", e.path("seq").asInt(0));
        o.put("ts", e.path("ts").asText(""));
        o.put("type", e.path("type").asText(""));
        o.put("actor", e.path("actor").asText(""));
        o.put("name", e.path("name").asText(""));
        o.put("summary", e.path("summary").asText(""));
        if (!e.path("durationMs").isMissingNode() && !e.path("durationMs").isNull())
            o.put("durationMs", e.path("durationMs").asLong());
        if (e.path("costUsd").asDouble(0) != 0) o.put("costUsd", e.path("costUsd").asDouble(0));
        if (e.path("tags").isArray() && !e.path("tags").isEmpty()) o.set("tags", e.path("tags"));
        return o;
    }
}
