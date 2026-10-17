package com.agenttrail.server.service;

import com.agenttrail.server.domain.EventEntity;
import com.agenttrail.server.domain.SessionEntity;
import com.agenttrail.server.dto.Dtos.BatchEventRequest;
import com.agenttrail.server.dto.Dtos.BatchItemResult;
import com.agenttrail.server.dto.Dtos.BatchResponse;
import com.agenttrail.server.dto.Dtos.CreateEventRequest;
import com.agenttrail.server.dto.Dtos.EventResponse;
import com.agenttrail.server.dto.Dtos.PageResponse;
import com.agenttrail.server.repo.EventRepository;
import com.agenttrail.server.repo.SessionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {

  private final SessionRepository sessions;
  private final EventRepository events;
  /** Self-reference through the proxy so batch retries run in a fresh transaction. */
  private final ObjectProvider<EventService> self;

  public EventService(
      SessionRepository sessions, EventRepository events, ObjectProvider<EventService> self) {
    this.sessions = sessions;
    this.events = events;
    this.self = self;
  }

  /**
   * Idempotent single ingest: a repeat with the same client-supplied {@code id}
   * returns the existing event instead of duplicating it.
   */
  @Transactional
  public EventResult ingest(UUID sessionId, CreateEventRequest req) {
    requireSession(sessionId);
    if (req.id() != null) {
      var existing = events.findById(req.id());
      if (existing.isPresent()) {
        return new EventResult(Mappers.toEventResponse(existing.get()), false);
      }
    }
    int seq = events.maxSeqForSession(sessionId) + 1;
    EventEntity saved = events.save(toEntity(sessionId, req, seq));
    return new EventResult(Mappers.toEventResponse(saved), true);
  }

  /**
   * Batch ingest: one SERIALIZABLE transaction per attempt. Seq numbers are assigned
   * consecutively from MAX(seq)+1; the UNIQUE(session_id, seq) constraint is the
   * backstop, with a single retry in a fresh transaction on conflict
   * (see server/NOTES.md).
   */
  public BatchResponse ingestBatch(UUID sessionId, BatchEventRequest req) {
    try {
      return self.getObject().ingestBatchTx(sessionId, req);
    } catch (DataIntegrityViolationException ex) {
      // A concurrent batch likely raced us on seq assignment — retry once in a new
      // transaction; the unique constraint guarantees no silent duplicates either way.
      return self.getObject().ingestBatchTx(sessionId, req);
    }
  }

  @Transactional(isolation = Isolation.SERIALIZABLE)
  public BatchResponse ingestBatchTx(UUID sessionId, BatchEventRequest req) {
    requireSession(sessionId);
    List<BatchItemResult> results = new ArrayList<>();
    int nextSeq = events.maxSeqForSession(sessionId) + 1;
    for (CreateEventRequest item : req.events()) {
      if (item.id() != null) {
        var existing = events.findById(item.id());
        if (existing.isPresent()) {
          EventEntity e = existing.get();
          results.add(new BatchItemResult(e.getId(), "duplicate", e.getSeq()));
          continue;
        }
      }
      EventEntity saved = events.save(toEntity(sessionId, item, nextSeq));
      results.add(new BatchItemResult(saved.getId(), "created", saved.getSeq()));
      nextSeq++;
    }
    events.flush(); // surface constraint violations inside this attempt, so the retry can catch them
    return new BatchResponse(results);
  }

  @Transactional(readOnly = true)
  public PageResponse<EventResponse> timeline(
      UUID sessionId,
      List<String> types,
      String actor,
      String q,
      Instant from,
      Instant to,
      int page,
      int size) {
    requireSession(sessionId);
    Page<EventEntity> result =
        events.search(
            sessionId,
            (types == null || types.isEmpty()) ? null : types,
            blankToNull(actor),
            blankToNull(q),
            from,
            to,
            PageRequest.of(page, clampSize(size)));
    List<EventResponse> content = result.getContent().stream().map(Mappers::toEventResponse).toList();
    return new PageResponse<>(
        content, page, clampSize(size), result.getTotalElements(), result.getTotalPages());
  }

  // ---------- internals ----------

  private SessionEntity requireSession(UUID sessionId) {
    return sessions
        .findById(sessionId)
        .orElseThrow(() -> new NotFoundException("session not found: " + sessionId));
  }

  private static EventEntity toEntity(UUID sessionId, CreateEventRequest req, int seq) {
    var e = new EventEntity();
    e.setId(req.id() != null ? req.id() : UUID.randomUUID());
    e.setSessionId(sessionId);
    e.setSeq(seq);
    e.setTs(req.ts());
    e.setType(req.type());
    e.setActor(req.actor());
    e.setName(req.name());
    e.setSummary(req.summary());
    e.setDetail(req.detail());
    e.setDurationMs(req.durationMs());
    e.setTokensIn(req.tokensIn() != null ? req.tokensIn() : 0);
    e.setTokensOut(req.tokensOut() != null ? req.tokensOut() : 0);
    e.setCostUsd(req.costUsd() != null ? req.costUsd() : BigDecimal.ZERO);
    e.setParentEventId(req.parentEventId());
    e.setTags(req.tags());
    return e;
  }

  private static String blankToNull(String v) {
    return (v == null || v.isBlank()) ? null : v;
  }

  private static int clampSize(int size) {
    return Math.min(Math.max(size, 1), 200);
  }

  public record EventResult(EventResponse response, boolean created) {}
}
