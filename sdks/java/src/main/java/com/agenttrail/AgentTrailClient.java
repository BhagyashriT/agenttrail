package com.agenttrail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dependency-free client for the AgentTrail v1 API
 * (see {@code docs/API_CONTRACT.md}).
 *
 * <p>Events are batched client-side and flushed by a background thread every
 * {@code flushIntervalSeconds} seconds or whenever {@code batchSize} events
 * accumulate. Every event carries a client-generated UUID {@code id} so
 * retries are idempotent. Multi-event flushes use
 * {@code POST /api/v1/sessions/{id}/events:batch}; a lone event uses
 * {@code POST /api/v1/sessions/{id}/events}.
 *
 * <pre>{@code
 * try (AgentTrailClient client = new AgentTrailClient()) { // AGENTTRAIL_URL / AGENTTRAIL_API_KEY
 *     client.startSession("custom", "debug checkout flake");
 *     client.message("user", "The checkout test is flaky");
 *     try (TimedTool t = client.tool("Bash", "ran unit tests")) {
 *         runTests();
 *     }
 *     client.cost(1200, 800, 0.012);
 *     client.endSession("completed");
 * }
 * }</pre>
 *
 * <p>HTTP failures are retried with exponential backoff; an
 * {@link AgentTrailException} is thrown only after retries are exhausted.
 * Background flushes are best-effort (the last error is available via
 * {@link #getLastFlushError()}); explicit {@link #flush()} calls raise.
 */
public class AgentTrailClient implements AutoCloseable {

    /** Default server URL, overridable with env {@code AGENTTRAIL_URL}. */
    public static final String DEFAULT_URL = "http://localhost:8080";
    /** Default API key, overridable with env {@code AGENTTRAIL_API_KEY}. */
    public static final String DEFAULT_API_KEY = "dev-key";
    /** Maximum event summary length per the API contract. */
    public static final int SUMMARY_LIMIT = 280;

    private static final String TRUNC_SUFFIX = "...(truncated)";

    private final String serverUrl;
    private final String apiKey;
    private final int batchSize;
    private final long flushIntervalSeconds;
    private final int maxRetries;
    private final Duration timeout;

    private final HttpClient http;
    private final ScheduledExecutorService scheduler;
    private final Object lock = new Object();
    private final Deque<Map<String, Object>> queue = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile String sessionId;
    private volatile Exception lastFlushError;

    /** Creates a client using {@code AGENTTRAIL_URL} / {@code AGENTTRAIL_API_KEY} env defaults. */
    public AgentTrailClient() {
        this(builder());
    }

    /** Creates a client with an explicit server URL and API key. */
    public AgentTrailClient(String serverUrl, String apiKey) {
        this(builder().serverUrl(serverUrl).apiKey(apiKey));
    }

    private AgentTrailClient(Builder builder) {
        this.serverUrl = stripTrailingSlash(builder.serverUrl);
        this.apiKey = builder.apiKey;
        this.batchSize = builder.batchSize;
        this.flushIntervalSeconds = builder.flushIntervalSeconds;
        this.maxRetries = builder.maxRetries;
        this.timeout = builder.timeout;
        this.sessionId = builder.sessionId;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agenttrail-flusher");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleWithFixedDelay(
                this::backgroundFlush, flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------- builder
    public static final class Builder {
        private String serverUrl = env("AGENTTRAIL_URL", DEFAULT_URL);
        private String apiKey = env("AGENTTRAIL_API_KEY", DEFAULT_API_KEY);
        private String sessionId;
        private int batchSize = 50;
        private long flushIntervalSeconds = 5;
        private int maxRetries = 3;
        private Duration timeout = Duration.ofSeconds(10);

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return (value == null || value.isBlank()) ? fallback : value;
        }

        public Builder serverUrl(String serverUrl) {
            this.serverUrl = serverUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** Attach to an existing session instead of creating one. */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /** Flush when this many events accumulate (default 50). */
        public Builder batchSize(int batchSize) {
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize must be >= 1");
            }
            this.batchSize = batchSize;
            return this;
        }

        /** Background flush interval in seconds (default 5). */
        public Builder flushIntervalSeconds(long flushIntervalSeconds) {
            if (flushIntervalSeconds < 1) {
                throw new IllegalArgumentException("flushIntervalSeconds must be >= 1");
            }
            this.flushIntervalSeconds = flushIntervalSeconds;
            return this;
        }

        /** Retries per request after the first attempt (default 3). */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must be >= 0");
            }
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public AgentTrailClient build() {
            if (serverUrl == null || serverUrl.isBlank()) {
                throw new IllegalArgumentException("serverUrl is required");
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("apiKey is required");
            }
            return new AgentTrailClient(this);
        }
    }

    // ------------------------------------------------------------------ HTTP
    private String request(String method, String path, String jsonBody) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-API-Key", apiKey);
        if (jsonBody != null) {
            requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
            requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpRequest request = requestBuilder.build();

        Throwable lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body() == null ? "" : response.body();
                }
                if (status >= 500 && attempt < maxRetries) {
                    lastError = new IOException("HTTP " + status);
                    backoff(attempt);
                    continue;
                }
                String detail = response.body();
                throw new AgentTrailException("AgentTrail " + method + " " + path
                        + " failed: HTTP " + status
                        + (detail != null && !detail.isBlank() ? " " + trunc(detail, 300) : ""));
            } catch (IOException e) {
                lastError = e;
                if (attempt < maxRetries) {
                    backoff(attempt);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentTrailException("AgentTrail " + method + " " + path + " interrupted", e);
            }
        }
        throw new AgentTrailException(
                "AgentTrail " + method + " " + path + " failed after " + maxRetries
                        + " retries: " + lastError,
                lastError instanceof Exception e ? e : null);
    }

    private void backoff(int attempt) {
        long ms = Math.min((long) (Math.pow(2, attempt) * 500), 8000);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentTrailException("interrupted during retry backoff", e);
        }
    }

    // --------------------------------------------------------------- batching
    /** Creates a new event map; {@code id}, {@code ts} and {@code actor} defaults are filled on enqueue. */
    Map<String, Object> newEvent(String type) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        return event;
    }

    void enqueue(Map<String, Object> event) {
        if (closed.get()) {
            throw new AgentTrailException("client is closed");
        }
        event.putIfAbsent("id", UUID.randomUUID().toString());
        event.putIfAbsent("ts", utcNow());
        event.putIfAbsent("actor", "agent");
        List<Map<String, Object>> ready = null;
        synchronized (lock) {
            queue.addLast(event);
            if (queue.size() >= batchSize) {
                ready = new ArrayList<>(queue);
                queue.clear();
            }
        }
        if (ready != null) {
            sendBatch(ready);
        }
    }

    private void sendBatch(List<Map<String, Object>> batch) {
        requireSession();
        String path = "/api/v1/sessions/" + sessionId
                + (batch.size() == 1 ? "/events" : "/events:batch");
        Object body = batch.size() == 1 ? batch.get(0) : Map.of("events", batch);
        request("POST", path, Json.encode(body));
    }

    private void backgroundFlush() {
        if (closed.get() || sessionId == null) {
            return;
        }
        try {
            flush();
        } catch (Exception e) {
            lastFlushError = e;
        }
    }

    /** Sends all queued events now. Raises {@link AgentTrailException} on persistent failure. */
    public void flush() {
        List<Map<String, Object>> batch;
        synchronized (lock) {
            if (queue.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(queue);
            queue.clear();
        }
        sendBatch(batch);
    }

    /** Flushes remaining events and stops the background thread. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdownNow();
            flush();
        }
    }

    /** The error from the most recent failed background flush, if any. */
    public Exception getLastFlushError() {
        return lastFlushError;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** The current session id, or {@code null} before {@link #startSession}. */
    public String sessionId() {
        return sessionId;
    }

    private void requireSession() {
        if (sessionId == null) {
            throw new AgentTrailException("no session: call startSession() first");
        }
    }

    // --------------------------------------------------------------- sessions
    /** Starts a session with just an agent type. Returns the server-assigned session id. */
    public String startSession(String agentType) {
        return startSession(SessionOptions.builder().agentType(agentType).build());
    }

    /** Starts a session with an agent type and a human-readable summary. */
    public String startSession(String agentType, String summary) {
        return startSession(SessionOptions.builder().agentType(agentType).summary(summary).build());
    }

    /**
     * Creates a session ({@code POST /api/v1/sessions}) and posts a
     * {@code session_start} event. Returns the server-assigned session id.
     */
    public String startSession(SessionOptions options) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentType", options.agentType());
        if (options.agentVersion() != null) {
            body.put("agentVersion", options.agentVersion());
        }
        if (options.model() != null) {
            body.put("model", options.model());
        }
        if (options.cwd() != null) {
            body.put("cwd", options.cwd());
        }
        if (options.summary() != null) {
            body.put("summary", options.summary());
        }
        body.put("metadata", options.metadata());
        body.put("clientSessionId", options.clientSessionId() != null
                ? options.clientSessionId() : UUID.randomUUID().toString());

        String responseBody = request("POST", "/api/v1/sessions", Json.encode(body));
        String id = Json.findString(responseBody, "id");
        if (id == null || id.isBlank()) {
            throw new AgentTrailException("session create returned no id");
        }
        this.sessionId = id;

        Map<String, Object> started = newEvent("session_start");
        started.put("actor", "system");
        started.put("summary", trunc(
                options.summary() != null ? options.summary() : "session started", SUMMARY_LIMIT));
        enqueue(started);
        return id;
    }

    /** Posts {@code session_end}, flushes, PATCHes the session status, and closes the client. */
    public void endSession(String status) {
        requireSession();
        Map<String, Object> ended = newEvent("session_end");
        ended.put("actor", "system");
        ended.put("summary", trunc("session ended (" + status + ")", SUMMARY_LIMIT));
        enqueue(ended);
        flush();
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("status", status);
        request("PATCH", "/api/v1/sessions/" + sessionId, Json.encode(patch));
        close();
    }

    /** Ends the session with status {@code "completed"}. */
    public void endSession() {
        endSession("completed");
    }

    // ----------------------------------------------------------------- events
    /** Starts timing a tool call; posts a {@code tool_call} event when the returned resource closes. */
    public TimedTool tool(String name) {
        return new TimedTool(this, name);
    }

    public TimedTool tool(String name, String summary) {
        return tool(name).setSummary(summary);
    }

    public TimedTool tool(String name, String summary, Map<String, Object> detail) {
        return tool(name).setSummary(summary).setDetail(detail);
    }

    /** Posts a {@code message} event. Role maps to actor: user/system/agent (assistant -> agent). */
    public void message(String role, String text) {
        String actor = switch (role == null ? "" : role) {
            case "user" -> "user";
            case "system" -> "system";
            case "agent", "assistant" -> "agent";
            default -> "agent";
        };
        String clean = text == null ? "" : text.strip();
        String firstLine = clean.isEmpty() ? "" : clean.split("\\R", 2)[0];
        Map<String, Object> event = newEvent("message");
        event.put("actor", actor);
        event.put("name", role + " message");
        event.put("summary", trunc(firstLine, SUMMARY_LIMIT));
        event.put("detail", Map.of("text", trunc(text == null ? "" : text, 4000)));
        enqueue(event);
    }

    /** Posts an {@code error} event. */
    public void error(String name, String summary) {
        error(name, summary, Map.of(), List.of());
    }

    public void error(String name, String summary, Map<String, Object> detail) {
        error(name, summary, detail, List.of());
    }

    public void error(String name, String summary, Map<String, Object> detail, List<String> tags) {
        Map<String, Object> event = newEvent("error");
        event.put("name", name);
        event.put("summary", trunc(summary, SUMMARY_LIMIT));
        event.put("detail", detail == null ? Map.of() : detail);
        event.put("tags", tags == null ? List.of() : tags);
        enqueue(event);
    }

    /** Posts a {@code handoff} event to a subagent. */
    public void handoff(String to, String summary) {
        handoff(to, summary, null);
    }

    public void handoff(String to, String summary, Map<String, Object> detail) {
        Map<String, Object> event = newEvent("handoff");
        event.put("actor", "subagent:" + to);
        event.put("name", "handoff");
        event.put("summary", trunc(summary, SUMMARY_LIMIT));
        Map<String, Object> merged = new LinkedHashMap<>();
        if (detail != null) {
            merged.putAll(detail);
        }
        merged.putIfAbsent("to", to);
        event.put("detail", merged);
        enqueue(event);
    }

    /** Posts a {@code file_change} event (action: create/edit/delete). */
    public void fileChange(String path, String summary) {
        fileChange(path, "edit", summary);
    }

    public void fileChange(String path, String action, String summary) {
        Map<String, Object> event = newEvent("file_change");
        event.put("name", path);
        event.put("summary", trunc(summary != null ? summary : action + " " + path, SUMMARY_LIMIT));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("path", path);
        detail.put("action", action);
        event.put("detail", detail);
        enqueue(event);
    }

    /** Posts a {@code cost} event with token/cost accounting. */
    public void cost(long tokensIn, long tokensOut, double costUsd) {
        cost(tokensIn, tokensOut, costUsd, null);
    }

    public void cost(long tokensIn, long tokensOut, double costUsd, String summary) {
        Map<String, Object> event = newEvent("cost");
        event.put("summary", trunc(summary != null ? summary
                : String.format(Locale.ROOT, "cost: %d in / %d out tokens ($%.4f)",
                        tokensIn, tokensOut, costUsd),
                SUMMARY_LIMIT));
        event.put("tokensIn", tokensIn);
        event.put("tokensOut", tokensOut);
        event.put("costUsd", costUsd);
        enqueue(event);
    }

    // ---------------------------------------------------------------- helpers
    static String utcNow() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    /** Truncates to {@code limit} characters total, appending a marker when truncated. */
    static String trunc(String value, int limit) {
        if (value == null) {
            return "";
        }
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit - TRUNC_SUFFIX.length()) + TRUNC_SUFFIX;
    }

    private static String stripTrailingSlash(String url) {
        int end = url.length();
        while (end > 1 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
