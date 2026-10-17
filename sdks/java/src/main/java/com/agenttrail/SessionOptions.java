package com.agenttrail;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Options for {@link AgentTrailClient#startSession(SessionOptions)}.
 *
 * <pre>{@code
 * client.startSession(SessionOptions.builder()
 *         .agentType("custom")
 *         .model("claude-opus-4-5")
 *         .cwd("/home/user/myproj")
 *         .summary("debug checkout flake")
 *         .metadataEntry("branch", "fix/npe")
 *         .build());
 * }</pre>
 */
public final class SessionOptions {

    private final String agentType;
    private final String agentVersion;
    private final String model;
    private final String cwd;
    private final String summary;
    private final Map<String, Object> metadata;
    private final String clientSessionId;

    private SessionOptions(Builder builder) {
        this.agentType = builder.agentType;
        this.agentVersion = builder.agentVersion;
        this.model = builder.model;
        this.cwd = builder.cwd;
        this.summary = builder.summary;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
        this.clientSessionId = builder.clientSessionId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String agentType() {
        return agentType;
    }

    public String agentVersion() {
        return agentVersion;
    }

    public String model() {
        return model;
    }

    public String cwd() {
        return cwd;
    }

    public String summary() {
        return summary;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public String clientSessionId() {
        return clientSessionId;
    }

    public static final class Builder {
        private String agentType;
        private String agentVersion;
        private String model;
        private String cwd;
        private String summary;
        private final Map<String, Object> metadata = new LinkedHashMap<>();
        private String clientSessionId;

        public Builder agentType(String agentType) {
            this.agentType = agentType;
            return this;
        }

        public Builder agentVersion(String agentVersion) {
            this.agentVersion = agentVersion;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder cwd(String cwd) {
            this.cwd = cwd;
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata.clear();
            if (metadata != null) {
                this.metadata.putAll(metadata);
            }
            return this;
        }

        public Builder metadataEntry(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        /**
         * Client-generated UUID for idempotent session creation; the server
         * returns the existing session instead of duplicating it.
         */
        public Builder clientSessionId(String clientSessionId) {
            this.clientSessionId = clientSessionId;
            return this;
        }

        public SessionOptions build() {
            if (agentType == null || agentType.isBlank()) {
                throw new IllegalArgumentException("agentType is required");
            }
            return new SessionOptions(this);
        }
    }
}
