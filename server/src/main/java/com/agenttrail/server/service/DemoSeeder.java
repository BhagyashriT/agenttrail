package com.agenttrail.server.service;

import com.agenttrail.server.dto.Dtos.BatchEventRequest;
import com.agenttrail.server.dto.Dtos.BatchResponse;
import com.agenttrail.server.dto.Dtos.CreateEventRequest;
import com.agenttrail.server.dto.Dtos.CreateSessionRequest;
import com.agenttrail.server.dto.Dtos.DemoSeedResponse;
import com.agenttrail.server.dto.Dtos.SessionResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Builds one realistic debugging session (~25 events): session_start, user
 * message, tool calls with durations, an error + fix file_change, a subagent
 * handoff, cost events, and session_end. Safe to call repeatedly — every call
 * creates a brand-new session.
 */
@Service
public class DemoSeeder {

  private final SessionService sessionService;
  private final EventService eventService;

  public DemoSeeder(SessionService sessionService, EventService eventService) {
    this.sessionService = sessionService;
    this.eventService = eventService;
  }

  public DemoSeedResponse seed() {
    var created =
        sessionService.create(
            new CreateSessionRequest(
                "claude-code",
                "2.0.1",
                "claude-opus-4-5",
                "/home/demo/shop",
                "Fixed NPE in checkout flow when cart is empty",
                Map.of("host", "demo-box", "branch", "fix/checkout-npe"),
                null));
    SessionResponse session = created.response();
    UUID sessionId = session.id();

    Instant t = Instant.now().minusSeconds(62 * 60); // session "started" ~1h ago
    List<CreateEventRequest> batch = new ArrayList<>();

    batch.add(ev("session_start", "system", null, "session started", null, t, 0L, 0, 0, "0", null, null));
    t = plus(t, 20);
    batch.add(ev("message", "user", null, "Checkout throws NPE when the cart is empty — can you fix it?",
        Map.of("channel", "cli"), t, 0L, 0, 0, "0", null, List.of("bug")));
    t = plus(t, 45);
    batch.add(ev("tool_call", "agent", "Read", "read CheckoutService.java (212 lines)",
        Map.of("path", "src/main/java/shop/CheckoutService.java", "lines", 212), t, 120L, 1800, 640, "0.0124", null, List.of("read")));
    t = plus(t, 60);
    batch.add(ev("tool_call", "agent", "Grep", "grep for getItems() call sites (6 matches)",
        Map.of("pattern", "getItems\\(\\)", "matches", 6), t, 210L, 900, 120, "0.0031", null, List.of("search")));
    t = plus(t, 40);
    batch.add(ev("tool_call", "agent", "Read", "read CheckoutServiceTest.java (88 lines)",
        Map.of("path", "src/test/java/shop/CheckoutServiceTest.java", "lines", 88), t, 95L, 1400, 420, "0.0098", null, List.of("read")));
    t = plus(t, 90);
    batch.add(ev("tool_call", "agent", "Bash", "ran `npm test` (exit 1, 4.2s)",
        Map.of("command", "npm test", "exitCode", 1, "outputHead", "FAIL src/checkout.test.ts ... TypeError: Cannot read properties of null"),
        t, 4200L, 0, 0, "0", null, List.of("test", "failure")));
    t = plus(t, 15);
    batch.add(ev("error", "agent", "Bash", "NullPointerException at CheckoutService.java:142",
        Map.of("exception", "java.lang.NullPointerException", "file", "CheckoutService.java", "line", 142,
            "stackHead", "at shop.CheckoutService.total(CheckoutService.java:142)"),
        t, 0L, 0, 0, "0", null, List.of("bug", "npe")));
    t = plus(t, 15);
    batch.add(ev("tool_call", "agent", "Bash", "reproduced NPE with single test (exit 1, 1.1s)",
        Map.of("command", "npm test -- checkout.empty", "exitCode", 1),
        t, 1100L, 0, 0, "0", null, List.of("test", "repro")));
    t = plus(t, 120);
    batch.add(ev("message", "agent", null, "Found it: `cart.getItems()` is null for empty carts. `total()` dereferences it without a guard.",
        Map.of("confidence", "high"), t, 0L, 2400, 180, "0.0188", null, List.of("analysis")));
    t = plus(t, 30);
    batch.add(ev("handoff", "agent", null, "handed off to subagent:code-reviewer for a second pair of eyes",
        Map.of("to", "subagent:code-reviewer", "reason", "verify fix approach"), t, 0L, 400, 60, "0.0042",
        null, List.of("handoff")));
    t = plus(t, 150);
    batch.add(ev("message", "subagent:code-reviewer", null, "Agreed — null-guard in total() plus an empty-cart test.",
        Map.of("review", "approve"), t, 0L, 1200, 220, "0.0096", null, List.of("review")));
    t = plus(t, 40);
    batch.add(ev("file_change", "agent", "Edit", "added null-guard in CheckoutService.total()",
        Map.of("path", "src/main/java/shop/CheckoutService.java",
            "diffHead", "+ if (cart.getItems() == null) return BigDecimal.ZERO;"),
        t, 380L, 2600, 900, "0.0210", null, List.of("fix")));
    t = plus(t, 25);
    batch.add(ev("file_change", "agent", "Edit", "added empty-cart regression test",
        Map.of("path", "src/test/java/shop/CheckoutServiceTest.java", "test", "total_emptyCart_returnsZero"),
        t, 290L, 1800, 700, "0.0145", null, List.of("test")));
    t = plus(t, 70);
    batch.add(ev("tool_call", "agent", "Bash", "ran `npm test` (exit 0, 3.8s)",
        Map.of("command", "npm test", "exitCode", 0, "outputHead", "PASS 14 tests in 3.8s"),
        t, 3800L, 0, 0, "0", null, List.of("test", "pass")));
    t = plus(t, 35);
    batch.add(ev("tool_call", "agent", "Bash", "ran `./gradlew test --tests CheckoutServiceTest` (exit 0, 6.1s)",
        Map.of("command", "./gradlew test --tests CheckoutServiceTest", "exitCode", 0),
        t, 6100L, 0, 0, "0", null, List.of("test", "pass")));
    t = plus(t, 40);
    batch.add(ev("tool_call", "agent", "Bash", "ran `./gradlew checkstyleMain` (exit 0, 2.4s)",
        Map.of("command", "./gradlew checkstyleMain", "exitCode", 0),
        t, 2400L, 0, 0, "0", null, List.of("lint", "pass")));
    t = plus(t, 35);
    batch.add(ev("tool_call", "agent", "Bash", "ran `git diff --stat` (2 files changed, 21 insertions)",
        Map.of("command", "git diff --stat", "exitCode", 0, "filesChanged", 2),
        t, 320L, 0, 0, "0", null, List.of("git")));
    t = plus(t, 50);
    batch.add(ev("checkpoint", "system", null, "checkpoint: fix verified, tests green",
        Map.of("testsPassed", 15), t, 0L, 0, 0, "0", null, List.of("checkpoint")));
    t = plus(t, 20);
    batch.add(ev("cost", "system", null, "model usage this session: 12.4k in / 3.1k out",
        Map.of("provider", "anthropic"), t, 0L, 12400, 3100, "0.0621", null, List.of("cost")));
    t = plus(t, 90);
    batch.add(ev("message", "agent", null, "Fixed. `total()` now returns $0.00 for empty carts; added a regression test. All 15 tests pass.",
        null, t, 0L, 900, 240, "0.0078", null, List.of("summary")));
    t = plus(t, 30);
    batch.add(ev("approval", "user", null, "approved merge to fix/checkout-npe",
        Map.of("approved", true), t, 0L, 0, 0, "0", null, List.of("approval")));
    t = plus(t, 45);
    batch.add(ev("tool_call", "agent", "Bash", "ran `git push origin fix/checkout-npe` (exit 0, 1.9s)",
        Map.of("command", "git push origin fix/checkout-npe", "exitCode", 0),
        t, 1900L, 0, 0, "0", null, List.of("git")));
    t = plus(t, 30);
    batch.add(ev("message", "user", null, "Nice — merging now, thanks!",
        Map.of("channel", "cli"), t, 0L, 0, 0, "0", null, List.of("thanks")));
    t = plus(t, 60);
    batch.add(ev("cost", "system", null, "final model usage: 18.9k in / 4.6k out",
        Map.of("provider", "anthropic"), t, 0L, 6500, 1500, "0.0412", null, List.of("cost")));
    t = plus(t, 10);
    batch.add(ev("session_end", "system", null, "session ended",
        Map.of("eventsTotal", 21), t, 0L, 0, 0, "0", null, null));

    BatchResponse resp = eventService.ingestBatch(sessionId, new BatchEventRequest(batch));

    // Mark the session completed with the timestamp of its last event.
    sessionService.patch(
        sessionId,
        new com.agenttrail.server.dto.Dtos.PatchSessionRequest("completed", t, session.summary()));

    return new DemoSeedResponse(sessionId);
  }

  private static Instant plus(Instant t, long seconds) {
    return t.plusSeconds(seconds);
  }

  private static CreateEventRequest ev(
      String type, String actor, String name, String summary, Map<String, Object> detail,
      Instant ts, Long durationMs, Integer tokensIn, Integer tokensOut, String costUsd,
      UUID parentEventId, List<String> tags) {
    String s = summary != null && summary.length() > 280 ? summary.substring(0, 280) : summary;
    return new CreateEventRequest(
        null, type, ts, actor, name, s, detail, durationMs, tokensIn, tokensOut,
        new BigDecimal(costUsd), parentEventId, tags);
  }
}
