package com.agenttrail.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** All API request/response shapes. Records keep them immutable and concise. */
public final class Dtos {

  private Dtos() {}

  // ---------- errors ----------
  public record ErrorResponse(String error, String message) {}

  // ---------- pagination ----------
  public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}

  // ---------- stats ----------
  public record SessionStats(
      long eventCount, long errorCount, long toolCallCount, BigDecimal costUsd, long durationMs) {
    public static SessionStats empty() {
      return new SessionStats(0, 0, 0, BigDecimal.ZERO, 0);
    }
  }

  // ---------- sessions ----------
  public record CreateSessionRequest(
      String agentType,
      String agentVersion,
      String model,
      String cwd,
      String summary,
      Map<String, Object> metadata,
      UUID clientSessionId) {}

  public record PatchSessionRequest(String status, Instant endedAt, String summary) {}

  public record SessionResponse(
      UUID id,
      String agentType,
      String agentVersion,
      String model,
      String cwd,
      String status,
      Instant startedAt,
      Instant endedAt,
      String summary,
      Map<String, Object> metadata,
      SessionStats stats) {}

  // ---------- events ----------
  public record CreateEventRequest(
      UUID id,
      @NotBlank(message = "type is required") String type,
      @NotNull(message = "ts is required") Instant ts,
      String actor,
      String name,
      @Size(max = 280, message = "summary must be at most 280 characters") String summary,
      Map<String, Object> detail,
      Long durationMs,
      Integer tokensIn,
      Integer tokensOut,
      BigDecimal costUsd,
      UUID parentEventId,
      List<String> tags) {}

  public record BatchEventRequest(
      @NotNull(message = "events is required")
          @Size(max = 500, message = "at most 500 events per batch")
          List<@Valid CreateEventRequest> events) {}

  public record BatchItemResult(UUID id, String status, Integer seq) {}

  public record BatchResponse(List<BatchItemResult> results) {}

  public record EventResponse(
      UUID id,
      UUID sessionId,
      Integer seq,
      Instant ts,
      String type,
      String actor,
      String name,
      String summary,
      Map<String, Object> detail,
      Long durationMs,
      Integer tokensIn,
      Integer tokensOut,
      BigDecimal costUsd,
      UUID parentEventId,
      List<String> tags) {}

  // ---------- stats overview ----------
  public record NameCount(String name, long count) {}

  public record DayCost(String day, BigDecimal costUsd) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record StatsOverview(
      long sessions,
      long events,
      long toolCalls,
      long errors,
      BigDecimal totalCostUsd,
      long totalTokensIn,
      long totalTokensOut,
      long avgSessionDurationMs,
      List<NameCount> topTools,
      List<NameCount> errorsByType,
      List<DayCost> costByDay) {}

  // ---------- demo ----------
  public record DemoSeedResponse(UUID sessionId) {}
}
