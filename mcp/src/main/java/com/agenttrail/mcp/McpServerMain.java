package com.agenttrail.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Entry point: builds the MCP server on stdio and registers the four read tools.
 *
 * <p>Configuration comes from the environment:
 * <ul>
 *   <li>{@code AGENTTRAIL_BASE_URL} — AgentTrail API base URL (default {@code http://localhost:8080})
 *   <li>{@code AGENTTRAIL_API_KEY} — the API key (default {@code dev-key})
 * </ul>
 *
 * <p>Wire into a client with:
 * <pre>
 * {
 *   "mcpServers": {
 *     "agenttrail": {
 *       "command": "java",
 *       "args": ["-jar", "/path/to/agenttrail-mcp.jar"],
 *       "env": {"AGENTTRAIL_BASE_URL": "http://localhost:8080", "AGENTTRAIL_API_KEY": "dev-key"}
 *     }
 *   }
 * }
 * </pre>
 */
public final class McpServerMain {

    private McpServerMain() {}

    public static void main(String[] args) {
        AgentTrailClient client = AgentTrailClient.fromEnv();

        var spec = McpServer.sync(new StdioServerTransportProvider())
                .serverInfo("agenttrail-mcp", "0.1.0")
                .instructions("AgentTrail is a flight recorder for AI agent sessions. "
                        + "Use list_sessions to find runs (e.g. the failed ones), "
                        + "get_session_events to replay a session's timeline, "
                        + "search_events to find repeated failure modes across sessions, "
                        + "and cost_summary for token spend. "
                        + "When debugging a failed run, replay its events and point at "
                        + "the exact seq where things went wrong.");

        for (McpSchema.Tool tool : Tools.definitions()) {
            spec = spec.toolCall(tool,
                    (McpSyncServerExchange exchange, McpSchema.CallToolRequest request) ->
                            Tools.dispatch(client, request.name(), request.arguments()));
        }

        var server = spec.build();
        // stdio transport runs on the calling thread until the client disconnects.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            server.close();
        }
    }
}
