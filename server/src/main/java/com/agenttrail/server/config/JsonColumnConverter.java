package com.agenttrail.server.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import java.io.IOException;

/**
 * Shared Jackson helper for the JSON-as-TEXT column converters.
 *
 * Tradeoff (documented in server/NOTES.md): TEXT is portable across PostgreSQL
 * and H2 with zero DDL divergence, at the cost of no DB-side JSON operators.
 * If Postgres-only JSONB querying is ever needed, swap these converters for a
 * jsonb column type + PGobject mapping.
 */
public final class JsonColumnConverter {

  static final ObjectMapper MAPPER = new ObjectMapper();

  private JsonColumnConverter() {}

  static String toJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Cannot serialize JSON column", e);
    }
  }

  /** Converter for Map-shaped columns: {@code metadata}, {@code detail}. */
  public static class MapConverter implements AttributeConverter<java.util.Map<String, Object>, String> {
    @Override
    public String convertToDatabaseColumn(java.util.Map<String, Object> attribute) {
      if (attribute == null || attribute.isEmpty()) {
        return null;
      }
      return JsonColumnConverter.toJson(attribute);
    }

    @Override
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> convertToEntityAttribute(String dbData) {
      if (dbData == null || dbData.isBlank()) {
        return null;
      }
      try {
        return MAPPER.readValue(dbData, java.util.Map.class);
      } catch (IOException e) {
        throw new IllegalArgumentException("Cannot deserialize JSON column", e);
      }
    }
  }

  /** Converter for the {@code tags} string-list column. */
  public static class TagListConverter implements AttributeConverter<java.util.List<String>, String> {
    @Override
    public String convertToDatabaseColumn(java.util.List<String> attribute) {
      if (attribute == null || attribute.isEmpty()) {
        return null;
      }
      return JsonColumnConverter.toJson(attribute);
    }

    @Override
    public java.util.List<String> convertToEntityAttribute(String dbData) {
      if (dbData == null || dbData.isBlank()) {
        return java.util.Collections.emptyList();
      }
      try {
        return MAPPER.readValue(
            dbData, MAPPER.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
      } catch (IOException e) {
        throw new IllegalArgumentException("Cannot deserialize JSON column", e);
      }
    }
  }
}
