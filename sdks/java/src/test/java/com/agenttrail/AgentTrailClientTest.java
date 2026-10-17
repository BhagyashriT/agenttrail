package com.agenttrail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SDK contract tests against a stub API server built on the JDK's
 * {@code com.sun.net.httpserver.HttpServer}. The stub verifies the
 * {@code X-API-Key} header on every request (401 otherwise) and records
 * all traffic for payload-shape assertions.
 */
class AgentTrailClientTest {

    static final String API_KEY = "test-key-123";
    static final String SESSION_ID = "3f9d0000-0000-4000-8000-000000000001";

    static HttpServer server;
    static String baseUrl;
    static final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    record RecordedRequest(String method, String path, Map<String, List<String>> headers, String body) {
    }

    @BeforeAll
    static void startStub() throws IOException {
        // Prefer IPv6 loopback: in this sandbox Java's IPv4 TCP is intercepted
        // by the egress policy, so the stub must be reachable via ::1.
        // Falls back to 127.0.0.1 on hosts without IPv6 loopback.
        IOException lastError = null;
        for (String host : new String[]{"::1", "127.0.0.1"}) {
            try {
                server = HttpServer.create(new InetSocketAddress(host, 0), 0);
                String label = host.contains(":") ? "[" + host + "]" : host;
                baseUrl = "http://" + label + ":" + server.getAddress().getPort();
                server.createContext("/", AgentTrailClientTest::handle);
                server.start();
                return;
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw new IOException("could not bind stub server on ::1 or 127.0.0.1", lastError);
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    @BeforeEach
    void clearRequests() {
        requests.clear();
    }

    // ------------------------------------------------------------------ stub
    static void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new RecordedRequest(method, path, exchange.getRequestHeaders(), body));

        int status;
        String response;
        if (!API_KEY.equals(firstHeader(exchange, "X-API-Key"))) {
            status = 401;
            response = "{\"error\":\"unauthorized\"}";
        } else if (method.equals("POST") && path.equals("/api/v1/sessions")) {
            status = 201;
            response = "{\"id\":\"" + SESSION_ID + "\",\"agentType\":\"custom\",\"status\":\"running\"}";
        } else if (method.equals("POST") && path.matches("/api/v1/sessions/[^/]+/events")) {
            status = 201;
            response = "{\"id\":\"" + Json.findString(body, "id") + "\",\"status\":\"created\"}";
        } else if (method.equals("POST") && path.endsWith("/events:batch")) {
            status = 200;
            response = "{\"results\":[]}";
        } else if (method.equals("PATCH") && path.matches("/api/v1/sessions/[^/]+")) {
            status = 200;
            response = "{\"id\":\"" + SESSION_ID + "\",\"status\":\"completed\"}";
        } else {
            status = 404;
            response = "{\"error\":\"not_found\"}";
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String firstHeader(HttpExchange exchange, String name) {
        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    static AgentTrailClient newClient() {
        return AgentTrailClient.builder()
                .serverUrl(baseUrl)
                .apiKey(API_KEY)
                .batchSize(1000) // avoid size-triggered flushes mid-test
                .flushIntervalSeconds(3600) // effectively disable the background flusher
                .maxRetries(0)
                .build();
    }

    /** Every event posted to the stub, from both single-event and batch endpoints. */
    static List<Map<String, Object>> postedEvents() {
        List<Map<String, Object>> events = new ArrayList<>();
        for (RecordedRequest request : requests) {
            if (!request.method().equals("POST")) {
                continue;
            }
            if (request.path().endsWith("/events")) {
                events.add(MiniJson.obj(MiniJson.parse(request.body())));
            } else if (request.path().endsWith("/events:batch")) {
                Map<String, Object> wrapper = MiniJson.obj(MiniJson.parse(request.body()));
                for (Object event : (List<?>) wrapper.get("events")) {
                    events.add(MiniJson.obj(event));
                }
            }
        }
        return events;
    }

    static void assertApiKeyOnEveryRequest() {
        assertFalse(requests.isEmpty(), "stub saw no requests");
        for (RecordedRequest request : requests) {
            String key = null;
            for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
                if (entry.getKey().equalsIgnoreCase("X-API-Key") && !entry.getValue().isEmpty()) {
                    key = entry.getValue().get(0);
                }
            }
            assertEquals(API_KEY, key, "X-API-Key missing/wrong on " + request.method() + " " + request.path());
        }
    }

    static Map<String, Object> requireEvent(List<Map<String, Object>> events, String type) {
        return events.stream()
                .filter(e -> type.equals(e.get("type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no event of type " + type + " posted"));
    }

    // ------------------------------------------------------------------ tests
    @Test
    void timedToolPostsValidToolCallEvent() throws Exception {
        try (AgentTrailClient client = newClient()) {
            String id = client.startSession("custom", "timed tool test");
            assertEquals(SESSION_ID, id);

            try (TimedTool ignored = client.tool("Bash", "ran unit tests")) {
                Thread.sleep(10);
            }
            client.flush();
        }

        assertApiKeyOnEveryRequest();

        List<Map<String, Object>> events = postedEvents();
        assertEquals("session_start", requireEvent(events, "session_start").get("type"));

        Map<String, Object> toolCall = requireEvent(events, "tool_call");
        assertEquals("tool_call", toolCall.get("type"));
        assertEquals("Bash", toolCall.get("name"));

        String summary = (String) toolCall.get("summary");
        assertNotNull(summary);
        assertTrue(summary.length() <= 280, "summary exceeds 280 chars: " + summary.length());

        assertTrue(((Number) toolCall.get("durationMs")).longValue() >= 0);

        String ts = (String) toolCall.get("ts");
        assertNotNull(ts);
        assertTrue(ts.endsWith("Z"), "ts must be UTC ISO-8601 ending in Z: " + ts);

        String eventId = (String) toolCall.get("id");
        assertNotNull(eventId);
        assertFalse(eventId.isBlank(), "client-generated event id must be present");
    }

    @Test
    void timedToolFailureTagsFailureAndPostsErrorEvent() {
        try (AgentTrailClient client = newClient()) {
            client.startSession("custom");
            requests.clear(); // isolate the failure-path events

            assertThrows(IllegalStateException.class, () -> {
                try (TimedTool tool = client.tool("Bash")) {
                    try {
                        throw new IllegalStateException("boom");
                    } catch (RuntimeException e) {
                        tool.failed(e);
                        throw e;
                    }
                }
            });
            client.flush();
        }

        assertApiKeyOnEveryRequest();

        List<Map<String, Object>> events = postedEvents();
        Map<String, Object> toolCall = requireEvent(events, "tool_call");
        assertTrue(((List<?>) toolCall.get("tags")).contains("failure"),
                "failed tool_call must carry the failure tag");

        Map<String, Object> error = requireEvent(events, "error");
        assertTrue(((String) error.get("summary")).contains("boom"));
    }

    @Test
    void startEndSessionRoundTrip() {
        try (AgentTrailClient client = newClient()) {
            String id = client.startSession(SessionOptions.builder()
                    .agentType("custom")
                    .summary("roundtrip")
                    .build());
            assertEquals(SESSION_ID, id);

            client.message("user", "hello world");
            client.endSession("completed");
        }

        assertApiKeyOnEveryRequest();

        assertTrue(requests.stream().anyMatch(r ->
                        r.method().equals("POST") && r.path().equals("/api/v1/sessions")),
                "expected POST /api/v1/sessions");

        List<Map<String, Object>> events = postedEvents();
        requireEvent(events, "session_start");
        requireEvent(events, "session_end");
        Map<String, Object> message = requireEvent(events, "message");
        assertEquals("user", message.get("actor"));

        RecordedRequest patch = requests.stream()
                .filter(r -> r.method().equals("PATCH"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected PATCH session status"));
        assertEquals("/api/v1/sessions/" + SESSION_ID, patch.path());
        assertEquals("completed", MiniJson.obj(MiniJson.parse(patch.body())).get("status"));
    }

    // ------------------------------------------------------- mini JSON parser
    /**
     * Tiny JSON parser for test assertions only
     * (objects, arrays, strings, numbers, booleans, null).
     */
    static final class MiniJson {
        private final String s;
        private int i;

        private MiniJson(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            MiniJson p = new MiniJson(s);
            Object value = p.parseValue();
            p.ws();
            if (p.i != p.s.length()) {
                throw new IllegalArgumentException("trailing characters at " + p.i);
            }
            return value;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> obj(Object value) {
            return (Map<String, Object>) value;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private char peek() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            return s.charAt(i);
        }

        private void expect(String literal) {
            if (!s.startsWith(literal, i)) {
                throw new IllegalArgumentException("expected " + literal + " at " + i);
            }
            i += literal.length();
        }

        private Object parseValue() {
            ws();
            char c = peek();
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> {
                    expect("true");
                    yield Boolean.TRUE;
                }
                case 'f' -> {
                    expect("false");
                    yield Boolean.FALSE;
                }
                case 'n' -> {
                    expect("null");
                    yield null;
                }
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // {
            ws();
            if (peek() == '}') {
                i++;
                return map;
            }
            while (true) {
                ws();
                String key = parseString();
                ws();
                if (peek() != ':') {
                    throw new IllegalArgumentException("expected ':' at " + i);
                }
                i++;
                map.put(key, parseValue());
                ws();
                char c = peek();
                if (c == ',') {
                    i++;
                } else if (c == '}') {
                    i++;
                    return map;
                } else {
                    throw new IllegalArgumentException("expected ',' or '}' at " + i);
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            i++; // [
            ws();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                ws();
                char c = peek();
                if (c == ',') {
                    i++;
                } else if (c == ']') {
                    i++;
                    return list;
                } else {
                    throw new IllegalArgumentException("expected ',' or ']' at " + i);
                }
            }
        }

        private String parseString() {
            if (peek() != '"') {
                throw new IllegalArgumentException("expected '\"' at " + i);
            }
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw new IllegalArgumentException("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char n = s.charAt(i++);
                    switch (n) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + n);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Number parseNumber() {
            int start = i;
            while (i < s.length() && "-+0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String token = s.substring(start, i);
            try {
                return Long.parseLong(token);
            } catch (NumberFormatException e) {
                return Double.parseDouble(token);
            }
        }
    }
}
