package com.agenttrail.server.service;

import com.agenttrail.server.domain.EventEntity;
import com.agenttrail.server.domain.SessionEntity;
import com.agenttrail.server.dto.Dtos.DayCost;
import com.agenttrail.server.dto.Dtos.EventResponse;
import com.agenttrail.server.dto.Dtos.NameCount;
import com.agenttrail.server.dto.Dtos.PageResponse;
import com.agenttrail.server.dto.Dtos.StatsOverview;
import com.agenttrail.server.repo.EventRepository;
import com.agenttrail.server.repo.SessionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatsService {

  private final SessionRepository sessions;
  private final EventRepository events;

  public StatsService(SessionRepository sessions, EventRepository events) {
    this.sessions = sessions;
    this.events = events;
  }

  @Transactional(readOnly = true)
  public StatsOverview overview(String agentType, Instant from, Instant to) {
    String at = blankToNull(agentType);
    long sessionCount = sessions.countForStats(at, from, to);

    Object[] totals = events.overviewTotals(at, from, to).get(0);
    long eventCount = num(totals[0]);
    long toolCalls = num(totals[1]);
    long errors = num(totals[2]);
    BigDecimal totalCost = totals[3] == null ? BigDecimal.ZERO : (BigDecimal) totals[3];
    long tokensIn = num(totals[4]);
    long tokensOut = num(totals[5]);

    // Avg session duration: single session query, durations computed in Java
    // (portable across H2/Postgres — no dialect-specific epoch arithmetic).
    List<SessionEntity> matched = sessions.findForStats(at, from, to);
    long avgDurationMs = 0;
    if (!matched.isEmpty()) {
      long sum = 0;
      int withEnd = 0;
      for (SessionEntity s : matched) {
        if (s.getEndedAt() != null) {
          sum += Math.max(0, s.getEndedAt().toEpochMilli() - s.getStartedAt().toEpochMilli());
          withEnd++;
        }
      }
      avgDurationMs = withEnd == 0 ? 0 : sum / withEnd;
    }

    Pageable top = PageRequest.of(0, 10);
    List<NameCount> topTools = new ArrayList<>();
    for (Object[] row : events.topTools(at, from, to, top)) {
      topTools.add(new NameCount((String) row[0], num(row[1])));
    }
    List<NameCount> errorsByType = new ArrayList<>();
    for (Object[] row : events.errorsByTool(at, from, to, top)) {
      errorsByType.add(new NameCount((String) row[0], num(row[1])));
    }

    // Cost per UTC day, grouped in Java (portable date truncation).
    Map<LocalDate, BigDecimal> byDay = new TreeMap<>();
    for (Object[] row : events.costPoints(at, from, to)) {
      Instant ts = (Instant) row[0];
      BigDecimal cost = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
      byDay.merge(ts.atZone(ZoneOffset.UTC).toLocalDate(), cost, BigDecimal::add);
    }
    List<DayCost> costByDay = new ArrayList<>();
    for (Map.Entry<LocalDate, BigDecimal> e : byDay.entrySet()) {
      costByDay.add(new DayCost(e.getKey().toString(), e.getValue()));
    }

    return new StatsOverview(
        sessionCount,
        eventCount,
        toolCalls,
        errors,
        totalCost,
        tokensIn,
        tokensOut,
        avgDurationMs,
        topTools,
        errorsByType,
        costByDay);
  }

  @Transactional(readOnly = true)
  public PageResponse<EventResponse> errors(
      String agentType, Instant from, Instant to, int page, int size) {
    Page<EventEntity> result =
        events.findErrors(
            blankToNull(agentType), from, to, PageRequest.of(page, clampSize(size)));
    List<EventResponse> content = result.getContent().stream().map(Mappers::toEventResponse).toList();
    return new PageResponse<>(
        content, page, clampSize(size), result.getTotalElements(), result.getTotalPages());
  }

  private static long num(Object v) {
    return v == null ? 0 : ((Number) v).longValue();
  }

  private static String blankToNull(String v) {
    return (v == null || v.isBlank()) ? null : v;
  }

  private static int clampSize(int size) {
    return Math.min(Math.max(size, 1), 200);
  }
}
