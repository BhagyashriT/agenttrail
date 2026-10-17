package com.agenttrail.server.domain;

import com.agenttrail.server.config.JsonColumnConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "sessions")
public class SessionEntity {

  @Id
  private UUID id;

  @Column(name = "client_session_id", unique = true)
  private UUID clientSessionId;

  @Column(name = "agent_type")
  private String agentType;

  @Column(name = "agent_version")
  private String agentVersion;

  private String model;
  private String cwd;

  @Column(nullable = false)
  private String status = "running";

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "ended_at")
  private Instant endedAt;

  private String summary;

  @Convert(converter = JsonColumnConverter.MapConverter.class)
  @Column(columnDefinition = "TEXT")
  private Map<String, Object> metadata;

  // ---- getters / setters ----
  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
  public UUID getClientSessionId() { return clientSessionId; }
  public void setClientSessionId(UUID clientSessionId) { this.clientSessionId = clientSessionId; }
  public String getAgentType() { return agentType; }
  public void setAgentType(String agentType) { this.agentType = agentType; }
  public String getAgentVersion() { return agentVersion; }
  public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }
  public String getModel() { return model; }
  public void setModel(String model) { this.model = model; }
  public String getCwd() { return cwd; }
  public void setCwd(String cwd) { this.cwd = cwd; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public Instant getStartedAt() { return startedAt; }
  public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
  public Instant getEndedAt() { return endedAt; }
  public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
  public String getSummary() { return summary; }
  public void setSummary(String summary) { this.summary = summary; }
  public Map<String, Object> getMetadata() { return metadata; }
  public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
