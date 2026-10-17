package com.agenttrail.server.service;

import com.agenttrail.server.domain.EventEntity;
import com.agenttrail.server.domain.SessionEntity;
import com.agenttrail.server.dto.Dtos.EventResponse;
import com.agenttrail.server.dto.Dtos.SessionResponse;
import com.agenttrail.server.dto.Dtos.SessionStats;
import java.math.BigDecimal;

/** Entity → DTO mapping, kept in one place. */
final class Mappers {

  private Mappers() {}

  static SessionResponse toSessionResponse(SessionEntity s, SessionStats stats) {
    return new SessionResponse(
        s.getId(),
        s.getAgentType(),
        s.getAgentVersion(),
        s.getModel(),
        s.getCwd(),
        s.getStatus(),
        s.getStartedAt(),
        s.getEndedAt(),
        s.getSummary(),
        s.getMetadata(),
        stats);
  }

  static EventResponse toEventResponse(EventEntity e) {
    return new EventResponse(
        e.getId(),
        e.getSessionId(),
        e.getSeq(),
        e.getTs(),
        e.getType(),
        e.getActor(),
        e.getName(),
        e.getSummary(),
        e.getDetail(),
        e.getDurationMs(),
        e.getTokensIn(),
        e.getTokensOut(),
        e.getCostUsd() == null ? BigDecimal.ZERO : e.getCostUsd(),
        e.getParentEventId(),
        e.getTags());
  }

  /** Builds SessionStats from an aggregate row: (eventCount, errorCount, toolCallCount, costSum). */
  static SessionStats statsFromRow(Object[] row, long durationMs) {
    long eventCount = row[0] == null ? 0 : ((Number) row[0]).longValue();
    long errorCount = row[1] == null ? 0 : ((Number) row[1]).longValue();
    long toolCallCount = row[2] == null ? 0 : ((Number) row[2]).longValue();
    BigDecimal cost = row[3] == null ? BigDecimal.ZERO : (BigDecimal) row[3];
    return new SessionStats(eventCount, errorCount, toolCallCount, cost, durationMs);
  }

  static long durationMs(SessionEntity s) {
    java.time.Instant end = s.getEndedAt() != null ? s.getEndedAt() : java.time.Instant.now();
    return Math.max(0, end.toEpochMilli() - s.getStartedAt().toEpochMilli());
  }
}
