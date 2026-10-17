package com.agenttrail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Times a tool call and posts a {@code tool_call} event when closed.
 *
 * <pre>{@code
 * try (TimedTool t = client.tool("Bash", "ran unit tests")) {
 *     runTests();
 * }
 * }</pre>
 *
 * <p>To record a failure, mark it before the resource closes — the
 * {@code tool_call} is then tagged {@code failure} and an {@code error}
 * event is posted as well:
 *
 * <pre>{@code
 * try (TimedTool t = client.tool("Bash")) {
 *     try {
 *         riskyWork();
 *     } catch (RuntimeException e) {
 *         t.failed(e);
 *         throw e;
 *     }
 * }
 * }</pre>
 *
 * <p>(Unlike Python's context manager, {@link AutoCloseable#close()} cannot
 * see the in-flight exception, hence the explicit {@link #failed} marker.)
 */
public final class TimedTool implements AutoCloseable {

    private final AgentTrailClient client;
    private final String name;
    private final long startNanos = System.nanoTime();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private String summary;
    private Map<String, Object> detail = new LinkedHashMap<>();
    private List<String> tags = new ArrayList<>();
    private long tokensIn;
    private long tokensOut;
    private double costUsd;
    private Throwable failure;

    TimedTool(AgentTrailClient client, String name) {
        if (client == null) {
            throw new IllegalArgumentException("client is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name is required");
        }
        this.client = client;
        this.name = name;
    }

    /** Overrides the event summary (default: "<name> completed in Xs" or a failure description). */
    public TimedTool setSummary(String summary) {
        this.summary = summary;
        return this;
    }

    /** Replaces the event detail object. */
    public TimedTool setDetail(Map<String, Object> detail) {
        this.detail = detail == null ? new LinkedHashMap<>() : new LinkedHashMap<>(detail);
        return this;
    }

    /** Replaces the event tags. */
    public TimedTool setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
        return this;
    }

    /** Adds a single tag to the event. */
    public TimedTool addTag(String tag) {
        this.tags.add(tag);
        return this;
    }

    public TimedTool setTokens(long tokensIn, long tokensOut) {
        this.tokensIn = tokensIn;
        this.tokensOut = tokensOut;
        return this;
    }

    public TimedTool setCostUsd(double costUsd) {
        this.costUsd = costUsd;
        return this;
    }

    /**
     * Marks this tool call as failed. Call from a {@code catch} block before
     * the resource closes; the {@code tool_call} event is then tagged
     * {@code failure} and an {@code error} event is posted too.
     */
    public TimedTool failed(Throwable failure) {
        this.failure = failure;
        return this;
    }

    /** Elapsed time since the tool started, in milliseconds. */
    public long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        long durationMs = elapsedMs();
        boolean failed = failure != null;

        Map<String, Object> event = client.newEvent("tool_call");
        event.put("name", name);
        String eventSummary = summary;
        if (eventSummary == null || eventSummary.isBlank()) {
            eventSummary = failed
                    ? name + " failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage()
                    : name + " completed in "
                            + String.format(Locale.ROOT, "%.1fs", durationMs / 1000.0);
        }
        event.put("summary", AgentTrailClient.trunc(eventSummary, AgentTrailClient.SUMMARY_LIMIT));
        event.put("detail", detail);
        event.put("durationMs", durationMs);
        List<String> allTags = new ArrayList<>(tags);
        if (failed && !allTags.contains("failure")) {
            allTags.add("failure");
        }
        event.put("tags", allTags);
        event.put("tokensIn", tokensIn);
        event.put("tokensOut", tokensOut);
        event.put("costUsd", costUsd);
        client.enqueue(event);

        if (failed) {
            String errSummary = name + " failed: " + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage();
            Map<String, Object> errDetail = new LinkedHashMap<>();
            errDetail.put("exception", failure.getClass().getName() + ": " + failure.getMessage());
            client.error(name, errSummary, errDetail, List.of("failure"));
        }
    }
}
