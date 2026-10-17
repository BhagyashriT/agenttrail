package com.agenttrail.server.domain;

import com.agenttrail.server.config.JsonColumnConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(
    name = "events",
    uniqueConstraints = @UniqueConstraint(name = "uq_events_session_seq", columnNames = {"session_id", "seq"}))
public class EventEntity {

  @Id
  private UUID id;

  @Column(name = "session_id", nullable = false)
  private UUID sessionId;

  @Column(nullable = false)
  private Integer seq;

  @Column(nullable = false)
  private Instant ts;

  @Column(name = "event_type", nullable = false)
  private String type;

  private String actor;
  private String name;

  @Column(length = 280)
  private String summary;

  @Convert(converter = JsonColumnConverter.MapConverter.class)
  @Column(columnDefinition = "TEXT")
  private Map<String, Object> detail;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(name = "tokens_in")
  private Integer tokensIn;

  @Column(name = "tokens_out")
  private Integer tokensOut;

  @Column(name = "cost_usd", precision = 12, scale = 4)
  private BigDecimal costUsd;

  @Column(name = "parent_event_id")
  private UUID parentEventId;

  @Convert(converter = JsonColumnConverter.TagListConverter.class)
  @Column(columnDefinition = "TEXT")
  private List<String> tags;

  // ---- getters / setters ----
  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
  public UUID getSessionId() { return sessionId; }
  public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }
  public Integer getSeq() { return seq; }
  public void setSeq(Integer seq) { this.seq = seq; }
  public Instant getTs() { return ts; }
  public void setTs(Instant ts) { this.ts = ts; }
  public String getType() { return type; }
  public void setType(String type) { this.type = type; }
  public String getActor() { return actor; }
  public void setActor(String actor) { this.actor = actor; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getSummary() { return summary; }
  public void setSummary(String summary) { this.summary = summary; }
  public Map<String, Object> getDetail() { return detail; }
  public void setDetail(Map<String, Object> detail) { this.detail = detail; }
  public Long getDurationMs() { return durationMs; }
  public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
  public Integer getTokensIn() { return tokensIn; }
  public void setTokensIn(Integer tokensIn) { this.tokensIn = tokensIn; }
  public Integer getTokensOut() { return tokensOut; }
  public void setTokensOut(Integer tokensOut) { this.tokensOut = tokensOut; }
  public BigDecimal getCostUsd() { return costUsd; }
  public void setCostUsd(BigDecimal costUsd) { this.costUsd = costUsd; }
  public UUID getParentEventId() { return parentEventId; }
  public void setParentEventId(UUID parentEventId) { this.parentEventId = parentEventId; }
  public List<String> getTags() { return tags; }
  public void setTags(List<String> tags) { this.tags = tags; }
}
