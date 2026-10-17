package com.agenttrail.server.repo;

import com.agenttrail.server.domain.SessionEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SessionRepository extends JpaRepository<SessionEntity, UUID> {

  Optional<SessionEntity> findByClientSessionId(UUID clientSessionId);

  @Query(
      "select s from SessionEntity s "
          + "where (:agentType is null or s.agentType = :agentType) "
          + "and (:status is null or s.status = :status) "
          + "and (:q is null or lower(s.summary) like lower(concat('%', :q, '%')) "
          + "or lower(s.cwd) like lower(concat('%', :q, '%'))) "
          + "and (:from is null or s.startedAt >= :from) "
          + "and (:to is null or s.startedAt < :to)")
  Page<SessionEntity> search(
      String agentType, String status, String q, Instant from, Instant to, Pageable pageable);

  @Query(
      "select s from SessionEntity s "
          + "where (:agentType is null or s.agentType = :agentType) "
          + "and (:from is null or s.startedAt >= :from) "
          + "and (:to is null or s.startedAt < :to)")
  List<SessionEntity> findForStats(String agentType, Instant from, Instant to);

  @Query(
      "select count(s) from SessionEntity s "
          + "where (:agentType is null or s.agentType = :agentType) "
          + "and (:from is null or s.startedAt >= :from) "
          + "and (:to is null or s.startedAt < :to)")
  long countForStats(String agentType, Instant from, Instant to);
}
