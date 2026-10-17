package com.agenttrail.server.service;

import com.agenttrail.server.domain.SessionEntity;
import com.agenttrail.server.dto.Dtos.CreateSessionRequest;
import com.agenttrail.server.dto.Dtos.PageResponse;
import com.agenttrail.server.dto.Dtos.PatchSessionRequest;
import com.agenttrail.server.dto.Dtos.SessionResponse;
import com.agenttrail.server.dto.Dtos.SessionStats;
import com.agenttrail.server.repo.EventRepository;
import com.agenttrail.server.repo.SessionRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {

  private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "failed", "interrupted");

  private final SessionRepository sessions;
  private final EventRepository events;

  public SessionService(SessionRepository sessions, EventRepository events) {
    this.sessions = sessions;
    this.events = events;
  }

  /**
   * Idempotent create: a repeat with the same {@code clientSessionId} returns the
   * existing session instead of creating a duplicate.
   */
  @Transactional
  public SessionResponseWithStatus create(CreateSessionRequest req) {
    if (req.clientSessionId() != null) {
      var existing = sessions.findByClientSessionId(req.clientSessionId());
      if (existing.isPresent()) {
        return new SessionResponseWithStatus(toResponse(existing.get()), false);
      }
    }
    var s = new SessionEntity();
    s.setId(UUID.randomUUID());
    s.setClientSessionId(req.clientSessionId());
    s.setAgentType(req.agentType());
    s.setAgentVersion(req.agentVersion());
    s.setModel(req.model());
    s.setCwd(req.cwd());
    s.setStatus("running");
    s.setStartedAt(Instant.now());
    s.setSummary(req.summary());
    s.setMetadata(req.metadata());
    sessions.save(s);
    return new SessionResponseWithStatus(toResponse(s), true);
  }

  @Transactional(readOnly = true)
  public PageResponse<SessionResponse> list(
      String agentType, String status, String q, Instant from, Instant to, int page, int size) {
    Page<SessionEntity> result =
        sessions.search(
            blankToNull(agentType),
            blankToNull(status),
            blankToNull(q),
            from,
            to,
            PageRequest.of(page, clampSize(size)));
    List<UUID> ids = result.getContent().stream().map(SessionEntity::getId).toList();
    Map<UUID, SessionStats> statsBySession = aggregateStats(result.getContent());
    List<SessionResponse> content =
        result.getContent().stream()
            .map(
                s ->
                    Mappers.toSessionResponse(
                        s,
                        statsBySession.getOrDefault(
                            s.getId(),
                            new SessionStats(0, 0, 0, java.math.BigDecimal.ZERO, Mappers.durationMs(s)))))
            .toList();
    return new PageResponse<>(
        content, page, clampSize(size), result.getTotalElements(), result.getTotalPages());
  }

  @Transactional(readOnly = true)
  public SessionResponse get(UUID id) {
    return toResponse(require(id));
  }

  @Transactional
  public SessionResponse patch(UUID id, PatchSessionRequest req) {
    SessionEntity s = require(id);
    if (req.status() != null) {
      s.setStatus(req.status());
      if (TERMINAL_STATUSES.contains(req.status()) && req.endedAt() == null && s.getEndedAt() == null) {
        s.setEndedAt(Instant.now());
      }
    }
    if (req.endedAt() != null) {
      s.setEndedAt(req.endedAt());
    }
    if (req.summary() != null) {
      s.setSummary(req.summary());
    }
    return toResponse(s);
  }

  // ---------- internals ----------

  private SessionEntity require(UUID id) {
    return sessions
        .findById(id)
        .orElseThrow(() -> new NotFoundException("session not found: " + id));
  }

  private SessionResponse toResponse(SessionEntity s) {
    // Aggregate without GROUP BY always yields exactly one row.
    Object[] row = events.aggregateStatsForSession(s.getId()).get(0);
    SessionStats stats = Mappers.statsFromRow(row, Mappers.durationMs(s));
    return Mappers.toSessionResponse(s, stats);
  }

  /** One grouped query for the whole page — no N+1. */
  private Map<UUID, SessionStats> aggregateStats(List<SessionEntity> page) {
    Map<UUID, SessionEntity> byId = new HashMap<>();
    for (SessionEntity s : page) {
      byId.put(s.getId(), s);
    }
    Map<UUID, SessionStats> out = new HashMap<>();
    if (byId.isEmpty()) {
      return out;
    }
    for (Object[] row : events.aggregateStatsForSessions(List.copyOf(byId.keySet()))) {
      UUID sessionId = (UUID) row[0];
      long durationMs = Mappers.durationMs(byId.get(sessionId));
      out.put(sessionId, Mappers.statsFromRow(new Object[] {row[1], row[2], row[3], row[4]}, durationMs));
    }
    return out;
  }

  private static String blankToNull(String v) {
    return (v == null || v.isBlank()) ? null : v;
  }

  private static int clampSize(int size) {
    return Math.min(Math.max(size, 1), 200);
  }

  public record SessionResponseWithStatus(SessionResponse response, boolean created) {}
}
