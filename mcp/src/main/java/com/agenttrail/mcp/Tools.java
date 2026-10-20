package com.agenttrail.mcp;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The four tools this server exposes, each mapped to AgentTrail v1 API endpoints.
 *
 * <p>All tools are read-only: AgentTrail's value for an assistant is as a debugging
 * oracle ("replay the session, find the step that went wrong"), and the API's write
 * paths exist for agents that record their own runs, not for a querying client.
 */
public final class Tools {

    private Tools() {}

    /** Tool names in registration order. */
    public static final List<String> NAMES =
            List.of("list_sessions", "get_session_events", "search_events", "cost_summary");

    /** Builds one {@link McpSchema.Tool} per name with its JSON input schema. */
    public static List<McpSchema.Tool> definitions() {
        return List.of(
                McpSchema.Tool.builder().name("list_sessions")
                        .title("List agent sessions")
                        .description("List recorded AgentTrail sessions with per-session stats "
                                + "(event/error/tool counts, cost, duration). Filter by agent type, "
                                + "status, free-text query over summaries, and started-at range.")
                        .inputSchema("""
                                {"type":"object","properties":{
                                "agentType":{"type":"string","description":"e.g. claude-code, codex-cli, custom"},
                                "status":{"type":"string","enum":["running","completed","failed","interrupted"]},
                                "q":{"type":"string","description":"substring search over session summary and cwd"},
                                "from":{"type":"string","description":"ISO-8601 start of startedAt range"},
                                "to":{"type":"string","description":"ISO-8601 end of startedAt range"},
                                "page":{"type":"integer","default":0,"minimum":0},
                                "size":{"type":"integer","default":20,"minimum":1,"maximum":50}},
                                "additionalProperties":false}""")
                        .build(),
                McpSchema.Tool.builder().name("get_session_events")
                        .title("Get session events")
                        .description("Read the ordered event stream (seq-ordered) of one session: "
                                + "tool calls with durations and exit codes, errors with excerpts, "
                                + "handoffs, file changes, cost events. This is the session replay.")
                        .inputSchema("""
                                {"type":"object","properties":{
                                "sessionId":{"type":"string","description":"session UUID from list_sessions"},
                                "type":{"type":"array","items":{"type":"string"},"description":"event types, e.g. [\\"tool_call\\",\\"error\\"]"},
                                "actor":{"type":"string","enum":["agent","user","system"]},
                                "q":{"type":"string","description":"substring search over event summary and name"},
                                "page":{"type":"integer","default":0,"minimum":0},
                                "size":{"type":"integer","default":20,"minimum":1,"maximum":100}},
                                "required":["sessionId"],"additionalProperties":false}""")
                        .build(),
                McpSchema.Tool.builder().name("search_events")
                        .title("Search events across sessions")
                        .description("Free-text search over event summaries and tool names across "
                                + "recent sessions. Use it to find repeated failure modes "
                                + "(e.g. every time `npm test` exited non-zero this week).")
                        .inputSchema("""
                                {"type":"object","properties":{
                                "q":{"type":"string","description":"substring search over event summary and tool name"},
                                "agentType":{"type":"string"},
                                "status":{"type":"string","enum":["running","completed","failed","interrupted"]},
                                "from":{"type":"string","description":"ISO-8601 start of startedAt range"},
                                "to":{"type":"string","description":"ISO-8601 end of startedAt range"},
                                "maxSessions":{"type":"integer","default":20,"minimum":1,"maximum":50},
                                "limit":{"type":"integer","default":50,"minimum":1,"maximum":200}},
                                "required":["q"],"additionalProperties":false}""")
                        .build(),
                McpSchema.Tool.builder().name("cost_summary")
                        .title("Token cost summary")
                        .description("Aggregate token/cost rollup over a date range: totals, "
                                + "cost by day, and the most expensive sessions.")
                        .inputSchema("""
                                {"type":"object","properties":{
                                "from":{"type":"string","description":"ISO-8601 start of range"},
                                "to":{"type":"string","description":"ISO-8601 end of range"},
                                "agentType":{"type":"string"},
                                "topSessions":{"type":"integer","default":5,"minimum":1,"maximum":20}},
                                "additionalProperties":false}""")
                        .build());
    }

    /** Dispatches a tool call to the right {@link AgentTrailClient} method. */
    public static McpSchema.CallToolResult dispatch(AgentTrailClient client,
                                                   String toolName, Map<String, Object> args) {
        try {
            String result = switch (toolName) {
                case "list_sessions" -> client.listSessions(
                        str(args, "agentType"), str(args, "status"), str(args, "q"),
                        str(args, "from"), str(args, "to"), num(args, "page", 0), num(args, "size", 20));
                case "get_session_events" -> client.getSessionEvents(
                        required(args, "sessionId"), list(args, "type"), str(args, "actor"),
                        str(args, "q"), num(args, "page", 0), num(args, "size", 20));
                case "search_events" -> client.searchEvents(
                        required(args, "q"), str(args, "agentType"), str(args, "status"),
                        str(args, "from"), str(args, "to"),
                        num(args, "maxSessions", 20), num(args, "limit", 50));
                case "cost_summary" -> client.costSummary(
                        str(args, "from"), str(args, "to"), str(args, "agentType"),
                        num(args, "topSessions", 5));
                default -> throw new IllegalArgumentException("unknown tool: " + toolName);
            };
            return ok(result);
        } catch (AgentTrailClient.ApiException e) {
            return err("AgentTrail API error " + e.status + ": " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return err("bad arguments: " + e.getMessage());
        } catch (Exception e) {
            return err("tool failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static McpSchema.CallToolResult ok(String text) {
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(null, text)), false);
    }

    private static McpSchema.CallToolResult err(String text) {
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(null, text)), true);
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String required(Map<String, Object> args, String key) {
        String v = str(args, key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("'" + key + "' is required");
        return v;
    }

    private static int num(Map<String, Object> args, String key, int def) {
        Object v = args.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + key + "' must be an integer");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return List.of();
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) out.add(String.valueOf(o));
            return out;
        }
        return List.of(String.valueOf(v));
    }

    /** Validates every tool schema parsed cleanly as type=object with properties. */
    static void validateSchemas() {
        for (McpSchema.Tool t : definitions()) {
            McpSchema.JsonSchema schema = t.inputSchema();
            if (!"object".equals(schema.type()))
                throw new IllegalStateException("tool " + t.name() + " schema is not type=object");
            if (schema.properties() == null || schema.properties().isEmpty())
                throw new IllegalStateException("tool " + t.name() + " schema has no properties");
        }
    }
}
