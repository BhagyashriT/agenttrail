package com.agenttrail.server.repo;

import com.agenttrail.server.domain.EventEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EventRepository extends JpaRepository<EventEntity, UUID> {

  @Query("select coalesce(max(e.seq), 0) from EventEntity e where e.sessionId = :sessionId")
  int maxSeqForSession(UUID sessionId);

  /**
   * One aggregate row per session: (sessionId, eventCount, errorCount, toolCallCount, costSum).
   * SUM columns may be null when a session has no events — callers must null-check.
   */
  @Query(
      "select e.sessionId, count(e), "
          + "sum(case when e.type = 'error' then 1 else 0 end), "
          + "sum(case when e.type = 'tool_call' then 1 else 0 end), "
          + "sum(e.costUsd) "
          + "from EventEntity e where e.sessionId in :sessionIds group by e.sessionId")
  List<Object[]> aggregateStatsForSessions(List<UUID> sessionIds);

  /** Single aggregate row for one session, avoiding N+1 on session reads. */
  @Query(
      "select count(e), "
          + "sum(case when e.type = 'error' then 1 else 0 end), "
          + "sum(case when e.type = 'tool_call' then 1 else 0 end), "
          + "sum(e.costUsd) "
          + "from EventEntity e where e.sessionId = :sessionId")
  List<Object[]> aggregateStatsForSession(UUID sessionId);

  @Query(
      "select e from EventEntity e "
          + "where e.sessionId = :sessionId "
          + "and (:types is null or e.type in :types) "
          + "and (:actor is null or e.actor = :actor) "
          + "and (:q is null or lower(e.summary) like lower(concat('%', :q, '%')) "
          + "or lower(e.name) like lower(concat('%', :q, '%'))) "
          + "and (:from is null or e.ts >= :from) "
          + "and (:to is null or e.ts < :to) "
          + "order by e.seq asc")
  Page<EventEntity> search(
      UUID sessionId,
      List<String> types,
      String actor,
      String q,
      Instant from,
      Instant to,
      Pageable pageable);

  // ---------- stats overview aggregates ----------
  @Query(
      "select count(e), "
          + "sum(case when e.type = 'tool_call' then 1 else 0 end), "
          + "sum(case when e.type = 'error' then 1 else 0 end), "
          + "sum(e.costUsd), "
          + "sum(e.tokensIn), "
          + "sum(e.tokensOut) "
          + "from EventEntity e join SessionEntity s on e.sessionId = s.id "
          + "where (:agentType is null or s.agentType = :agentType) "
          + "and (:from is null or e.ts >= :from) "
          + "and (:to is null or e.ts < :to)")
  List<Object[]> overviewTotals(String agentType, Instant from, Instant to);

  @Query(
      "select e.name, count(e) "
          + "from EventEntity e join SessionEntity s on e.sessionId = s.id "
          + "where e.type = 'tool_call' "
          + "and (:agentType is null or s.agentType = :agentType) "
          + "and (:from is null or e.ts >= :from) "
          + "and (:to is null or e.ts < :to) "
          + "group by e.name order by count(e) desc")
  List<Object[]> topTools(String agentType, Instant from, Instant to, Pageable pageable);

  @Query(
      "select e.name, count(e) "
          + "from EventEntity e join SessionEntity s on e.sessionId = s.id "
          + "where e.type = 'error' "
          + "and (:agentType is null or s.agentType = :agentType) "
          + "and (:from is null or e.ts >= :from) "
          + "and (:to is null or e.ts < :to) "
          + "group by e.name order by count(e) desc")
  List<Object[]> errorsByTool(String agentType, Instant from, Instant to, Pageable pageable);

  @Query(
      "select e.ts, e.costUsd "
          + "from EventEntity e join SessionEntity s on e.sessionId = s.id "
          + "where (:agentType is null or s.agentType = :agentType) "
          + "and (:from is null or e.ts >= :from) "
          + "and (:to is null or e.ts < :to)")
  List<Object[]> costPoints(String agentType, Instant from, Instant to);

  // ---------- error listing ----------
  @Query(
      "select e from EventEntity e join SessionEntity s on e.sessionId = s.id "
          + "where e.type = 'error' "
          + "and (:agentType is null or s.agentType = :agentType) "
          + "and (:from is null or e.ts >= :from) "
          + "and (:to is null or e.ts < :to) "
          + "order by e.ts desc")
  Page<EventEntity> findErrors(String agentType, Instant from, Instant to, Pageable pageable);
}
