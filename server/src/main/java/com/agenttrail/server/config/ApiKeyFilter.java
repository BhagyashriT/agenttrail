package com.agenttrail.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires header {@code X-API-Key} == configured key for every /api/** route
 * except /api/v1/health. Failures return 401 {"error":"unauthorized",...}.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

  private final String apiKey;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public ApiKeyFilter(@Value("${agenttrail.api-key:dev-key}") String apiKey) {
    this.apiKey = apiKey;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !path.startsWith("/api/") || path.equals("/api/v1/health");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String provided = request.getHeader("X-API-Key");
    if (provided == null || !apiKey.equals(provided)) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      objectMapper.writeValue(
          response.getWriter(),
          java.util.Map.of(
              "error", "unauthorized", "message", "Missing or invalid X-API-Key header"));
      return;
    }
    chain.doFilter(request, response);
  }
}
