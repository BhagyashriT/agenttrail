package com.agenttrail.server.web;

import com.agenttrail.server.dto.Dtos.BatchEventRequest;
import com.agenttrail.server.dto.Dtos.BatchResponse;
import com.agenttrail.server.dto.Dtos.CreateEventRequest;
import com.agenttrail.server.dto.Dtos.CreateSessionRequest;
import com.agenttrail.server.dto.Dtos.DemoSeedResponse;
import com.agenttrail.server.dto.Dtos.EventResponse;
import com.agenttrail.server.dto.Dtos.PageResponse;
import com.agenttrail.server.dto.Dtos.PatchSessionRequest;
import com.agenttrail.server.dto.Dtos.SessionResponse;
import com.agenttrail.server.dto.Dtos.StatsOverview;
import com.agenttrail.server.service.DemoSeeder;
import com.agenttrail.server.service.EventService;
import com.agenttrail.server.service.SessionService;
import com.agenttrail.server.service.StatsService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ApiController {

  private final SessionService sessionService;
  private final EventService eventService;
  private final StatsService statsService;
  private final DemoSeeder demoSeeder;
  private final String version;

  public ApiController(
      SessionService sessionService,
      EventService eventService,
      StatsService statsService,
      DemoSeeder demoSeeder,
      @Value("${agenttrail.version:0.1.0}") String version) {
    this.sessionService = sessionService;
    this.eventService = eventService;
    this.statsService = statsService;
    this.demoSeeder = demoSeeder;
    this.version = version;
  }

  // ---------- health (unauthenticated) ----------
  @GetMapping("/health")
  public HealthResponse health() {
    return new HealthResponse("UP", version);
  }

  public record HealthResponse(String status, String version) {}

  // ---------- sessions ----------
  @PostMapping("/sessions")
  public ResponseEntity<SessionResponse> createSession(
      @RequestBody(required = false) CreateSessionRequest req) {
    var result = sessionService.create(req == null ? new CreateSessionRequest(null, null, null, null, null, null, null) : req);
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(result.response());
  }

  @GetMapping("/sessions")
  public PageResponse<SessionResponse> listSessions(
      @RequestParam(required = false) String agentType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return sessionService.list(agentType, status, q, from, to, page, size);
  }

  @GetMapping("/sessions/{id}")
  public SessionResponse getSession(@PathVariable UUID id) {
    return sessionService.get(id);
  }

  @PatchMapping("/sessions/{id}")
  public SessionResponse patchSession(
      @PathVariable UUID id, @RequestBody PatchSessionRequest req) {
    return sessionService.patch(id, req);
  }

  // ---------- events ----------
  @PostMapping("/sessions/{id}/events")
  public ResponseEntity<EventResponse> ingestEvent(
      @PathVariable UUID id, @Valid @RequestBody CreateEventRequest req) {
    var result = eventService.ingest(id, req);
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(result.response());
  }

  @PostMapping("/sessions/{id}/events:batch")
  public BatchResponse ingestBatch(
      @PathVariable UUID id, @Valid @RequestBody BatchEventRequest req) {
    return eventService.ingestBatch(id, req);
  }

  @GetMapping("/sessions/{id}/events")
  public PageResponse<EventResponse> timeline(
      @PathVariable UUID id,
      @RequestParam(required = false, name = "type") List<String> types,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return eventService.timeline(id, types, actor, q, from, to, page, size);
  }

  // ---------- stats ----------
  @GetMapping("/stats/overview")
  public StatsOverview overview(
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) String agentType) {
    return statsService.overview(agentType, from, to);
  }

  @GetMapping("/stats/errors")
  public PageResponse<EventResponse> errors(
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) String agentType,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return statsService.errors(agentType, from, to, page, size);
  }

  // ---------- demo ----------
  @PostMapping("/demo/seed")
  public DemoSeedResponse seedDemo() {
    return demoSeeder.seed();
  }
}
