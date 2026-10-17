package com.agenttrail.server;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiIntegrationTest {

  private static final String KEY = "dev-key";

  @Autowired private MockMvc mvc;

  // ---------- health ----------

  @Test
  void healthIsUnauthenticated() throws Exception {
    mvc.perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("UP")))
        .andExpect(jsonPath("$.version", is("0.1.0")));
  }

  // ---------- auth ----------

  @Test
  void missingApiKeyIs401() throws Exception {
    mvc.perform(get("/api/v1/sessions"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", is("unauthorized")));
  }

  @Test
  void wrongApiKeyIs401() throws Exception {
    mvc.perform(get("/api/v1/sessions").header("X-API-Key", "nope"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", is("unauthorized")));
  }

  // ---------- sessions ----------

  @Test
  void createSessionReturns201WithEmptyStats() throws Exception {
    mvc.perform(
            post("/api/v1/sessions")
                .header("X-API-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"agentType":"claude-code","model":"claude-opus-4-5","cwd":"/tmp/x","summary":"t","metadata":{"host":"mbp"}}"""))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNotEmpty())
        .andExpect(jsonPath("$.status", is("running")))
        .andExpect(jsonPath("$.stats.eventCount", is(0)))
        .andExpect(jsonPath("$.metadata.host", is("mbp")));
  }

  @Test
  void createSessionIsIdempotentOnClientSessionId() throws Exception {
    String clientId = UUID.randomUUID().toString();
    String body = "{\"agentType\":\"codex-cli\",\"clientSessionId\":\"" + clientId + "\"}";

    MvcResult first =
        mvc.perform(
                post("/api/v1/sessions")
                    .header("X-API-Key", KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    String firstId =
        com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.id");

    mvc.perform(
            post("/api/v1/sessions")
                .header("X-API-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", is(firstId)));
  }

  @Test
  void unknownSessionIs404() throws Exception {
    UUID random = UUID.randomUUID();
    mvc.perform(get("/api/v1/sessions/" + random).header("X-API-Key", KEY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error", is("not_found")));

    mvc.perform(
            post("/api/v1/sessions/" + random + "/events")
                .header("X-API-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"message\",\"ts\":\"2026-10-16T18:00:00Z\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void patchToTerminalStatusStampsEndedAt() throws Exception {
    String sessionId = createSession();

    mvc.perform(
            patch("/api/v1/sessions/" + sessionId)
                .header("X-API-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"completed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("completed")))
        .andExpect(jsonPath("$.endedAt").isNotEmpty());
  }

  // ---------- events ----------

  @Test
  void postEventsAssignSequentialSeqs() throws Exception {
    String sessionId = createSession();
    for (int i = 1; i <= 3; i++) {
      mvc.perform(
              post("/api/v1/sessions/" + sessionId + "/events")
                  .header("X-API-Key", KEY)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"type\":\"tool_call\",\"ts\":\"2026-10-16T18:0"
                          + i
                          + ":00Z\",\"name\":\"Bash\",\"summary\":\"run "
                          + i
                          + "\"}"))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.seq", is(i)));
    }
  }

  @Test
  void ingestEventIsIdempotentOnClientSuppliedId() throws Exception {
    String sessionId = createSession();
    String eventId = UUID.randomUUID().toString();
    String body =
        "{\"id\":\"" + eventId + "\",\"type\":\"message\",\"ts\":\"2026-10-16T18:00:00Z\"}";

    mvc.perform(
            post("/api/v1/sessions/" + sessionId + "/events")
                .header("X-API-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.seq", is(1)));

    mvc.perform(
            post("/api/v1/sessions/" + sessionId + "/events")
                .header("X-API-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", is(eventId)))
        .andExpect(jsonPath("$.seq", is(1)));
  }

  @Test
  void batchIngestHandlesDuplicateClientIds() throws Exception {
    String sessionId = createSession();
    String dupId = UUID.randomUUID().toString();
    String body =
        """
        {"events":[
          {"id":"%s","type":"tool_call","ts":"2026-10-16T18:00:00Z","name":"Bash","costUsd":1.5},
          {"type":"error","ts":"2026-10-16T18:01:00Z","name":"Bash","summary":"boom","costUsd":0.25},
          {"id":"%s","type":"tool_call","ts":"2026-10-16T18:02:00Z","name":"Edit"}
        ]}"""
            .formatted(dupId, dupId);

    mvc.perform(
            post("/api/v1/sessions/" + sessionId + "/events:batch")
                .header("X-API-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results", hasSize(3)))
        .andExpect(jsonPath("$.results[0].status", is("created")))
        .andExpect(jsonPath("$.results[0].seq", is(1)))
        .andExpect(jsonPath("$.results[1].status", is("created")))
        .andExpect(jsonPath("$.results[1].seq", is(2)))
        .andExpect(jsonPath("$.results[2].status", is("duplicate")))
        .andExpect(jsonPath("$.results[2].seq", is(1)));
  }

  @Test
  void eventValidationRejectsMissingTypeAndLongSummary() throws Exception {
    String sessionId = createSession();

    mvc.perform(
            post("/api/v1/sessions/" + sessionId + "/events")
                .header("X-API-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ts\":\"2026-10-16T18:00:00Z\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", is("validation_error")));

    mvc.perform(
            post("/api/v1/sessions/" + sessionId + "/events")
                .header("X-API-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"type\":\"message\",\"ts\":\"2026-10-16T18:00:00Z\",\"summary\":\""
                        + "x".repeat(281)
                        + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", is("validation_error")));
  }

  @Test
  void timelineIsOrderedBySeqAndFilterable() throws Exception {
    String sessionId = createSession();
    postEvent(sessionId, "tool_call", "Bash", "first");
    postEvent(sessionId, "error", "Bash", "kaboom");
    postEvent(sessionId, "tool_call", "Edit", "second");

    mvc.perform(
            get("/api/v1/sessions/" + sessionId + "/events")
                .header("X-API-Key", KEY)
                .param("type", "tool_call"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(2)))
        .andExpect(jsonPath("$.content[0].seq", is(1)))
        .andExpect(jsonPath("$.content[1].seq", is(3)));
  }

  // ---------- stats ----------

  @Test
  void statsOverviewAggregatesCostAndErrors() throws Exception {
    String sessionId = createSession();
    String body =
        """
        {"events":[
          {"type":"tool_call","ts":"2026-10-16T18:00:00Z","name":"Bash","costUsd":1.5,"tokensIn":1000,"tokensOut":200},
          {"type":"tool_call","ts":"2026-10-16T18:00:30Z","name":"Bash","costUsd":0.5,"tokensIn":500,"tokensOut":100},
          {"type":"error","ts":"2026-10-16T18:01:00Z","name":"Bash","summary":"boom","costUsd":0.25},
          {"type":"tool_call","ts":"2026-10-16T18:02:00Z","name":"Edit","costUsd":0.5,"tokensIn":500,"tokensOut":100}
        ]}""";
    mvc.perform(
            post("/api/v1/sessions/" + sessionId + "/events:batch")
                .header("X-API-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/stats/overview").header("X-API-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.events", is(4)))
        .andExpect(jsonPath("$.toolCalls", is(3)))
        .andExpect(jsonPath("$.errors", is(1)))
        .andExpect(jsonPath("$.totalCostUsd", closeTo(2.75, 0.0001)))
        .andExpect(jsonPath("$.totalTokensIn", is(2000)))
        .andExpect(jsonPath("$.totalTokensOut", is(400)))
        .andExpect(jsonPath("$.topTools[0].name", is("Bash")))
        .andExpect(jsonPath("$.topTools[0].count", is(2)))
        .andExpect(jsonPath("$.errorsByType[0].name", is("Bash")))
        .andExpect(jsonPath("$.costByDay[0].day", is("2026-10-16")));

    mvc.perform(get("/api/v1/stats/errors").header("X-API-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(1)))
        .andExpect(jsonPath("$.content[0].type", is("error")))
        .andExpect(jsonPath("$.content[0].sessionId", is(sessionId)));
  }

  @Test
  void sessionStatsAreComputedOnRead() throws Exception {
    String sessionId = createSession();
    postEvent(sessionId, "tool_call", "Bash", "run");
    postEvent(sessionId, "error", "Bash", "boom with cost");

    mvc.perform(get("/api/v1/sessions/" + sessionId).header("X-API-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stats.eventCount", is(2)))
        .andExpect(jsonPath("$.stats.toolCallCount", is(1)))
        .andExpect(jsonPath("$.stats.errorCount", is(1)));
  }

  // ---------- demo ----------

  @Test
  void demoSeedReturnsSessionIdAndBuildsTimeline() throws Exception {
    MvcResult seeded =
        mvc.perform(post("/api/v1/demo/seed").header("X-API-Key", KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").isNotEmpty())
            .andReturn();
    String sessionId =
        com.jayway.jsonpath.JsonPath.read(seeded.getResponse().getContentAsString(), "$.sessionId");

    mvc.perform(
            get("/api/v1/sessions/" + sessionId + "/events")
                .header("X-API-Key", KEY)
                .param("size", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(25)))
        .andExpect(jsonPath("$.content[0].type", is("session_start")))
        .andExpect(jsonPath("$.content[0].seq", is(1)));

    mvc.perform(get("/api/v1/sessions/" + sessionId).header("X-API-Key", KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("completed")))
        .andExpect(jsonPath("$.endedAt").isNotEmpty())
        .andExpect(jsonPath("$.stats.errorCount", is(1)))
        .andExpect(jsonPath("$.stats.toolCallCount", is(10)));
  }

  // ---------- helpers ----------

  private String createSession() throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/v1/sessions")
                    .header("X-API-Key", KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"agentType\":\"claude-code\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    return com.jayway.jsonpath.JsonPath.read(
        result.getResponse().getContentAsString(), "$.id");
  }

  private void postEvent(String sessionId, String type, String name, String summary)
      throws Exception {
    mvc.perform(
            post("/api/v1/sessions/" + sessionId + "/events")
                .header("X-API-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"type\":\""
                        + type
                        + "\",\"ts\":\"2026-10-16T18:00:00Z\",\"name\":\""
                        + name
                        + "\",\"summary\":\""
                        + summary
                        + "\"}"))
        .andExpect(status().isCreated());
  }
}
