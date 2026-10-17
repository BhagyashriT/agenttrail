package com.agenttrail;

/**
 * Thrown when an AgentTrail API call fails after all retries are exhausted,
 * or when the client is used incorrectly (no session started, client closed).
 */
public class AgentTrailException extends RuntimeException {

    public AgentTrailException(String message) {
        super(message);
    }

    public AgentTrailException(String message, Throwable cause) {
        super(message, cause);
    }
}
