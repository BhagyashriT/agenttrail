package com.agenttrail;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal JSON serializer used by the SDK.
 *
 * <p>Supports maps, iterables, arrays, strings, numbers, booleans and null.
 * Strings are escaped per RFC 8259. This keeps the SDK dependency-free
 * (no Jackson).
 */
public final class Json {

    private Json() {
    }

    /** Serializes {@code value} to a JSON string. */
    public static String encode(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            escape(sb, s);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                escape(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                append(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                append(sb, item);
            }
            sb.append(']');
        } else if (value instanceof Object[] array) {
            sb.append('[');
            for (int i = 0; i < array.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                append(sb, array[i]);
            }
            sb.append(']');
        } else if (value instanceof Double d) {
            sb.append(d.isNaN() || d.isInfinite() ? "null" : d.toString());
        } else if (value instanceof Float f) {
            sb.append(f.isNaN() || f.isInfinite() ? "null" : f.toString());
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            // Last resort: stringify anything else.
            escape(sb, value.toString());
        }
    }

    private static void escape(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * Extracts a top-level string field from a JSON object. Returns
     * {@code null} when the field is absent. Used for small response
     * payloads (e.g. the session {@code id}); not a general parser.
     */
    public static String findString(String json, String key) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                .matcher(json);
        if (!m.find()) {
            return null;
        }
        return unescape(m.group(1));
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
