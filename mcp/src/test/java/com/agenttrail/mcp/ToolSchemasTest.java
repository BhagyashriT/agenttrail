package com.agenttrail.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for tool definitions, schemas, and dispatch. */
class ToolSchemasTest {

    @Test
    void schemasAreValidTypeObjectWithProperties() {
        Tools.validateSchemas();
    }

    @Test
    void fourToolsWithExpectedNames() {
        List<McpSchema.Tool> defs = Tools.definitions();
        assertThat(defs).hasSize(4);
        assertThat(defs.stream().map(McpSchema.Tool::name).toList())
                .containsExactlyElementsOf(Tools.NAMES);
    }

    @Test
    void requiredArgsAreEnforced() {
        McpSchema.CallToolResult r =
                Tools.dispatch(new AgentTrailClient("http://127.0.0.1:1", "x"),
                        "get_session_events", Map.of());
        assertThat(r.isError()).isTrue();
        assertThat(textOf(r)).contains("sessionId");
    }

    @Test
    void unknownToolIsAnErrorResult() {
        McpSchema.CallToolResult r =
                Tools.dispatch(new AgentTrailClient("http://127.0.0.1:1", "x"),
                        "nope", Map.of());
        assertThat(r.isError()).isTrue();
        assertThat(textOf(r)).contains("unknown tool");
    }

    @Test
    void unreachableApiIsAnErrorResultNotAnException() {
        // nothing listens on this port; dispatch must surface a clean error result
        McpSchema.CallToolResult r =
                Tools.dispatch(new AgentTrailClient("http://127.0.0.1:1", "x"),
                        "list_sessions", Map.of());
        assertThat(r.isError()).isTrue();
    }

    private static String textOf(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }
}
