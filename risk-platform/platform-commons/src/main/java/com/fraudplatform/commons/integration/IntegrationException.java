package com.fraudplatform.commons.integration;

/**
 * Typed failure of an outbound integration. The {@link Kind} drives caller behaviour: retry, degrade,
 * alert, or treat as "not found". Callers never parse messages.
 */
public class IntegrationException extends RuntimeException {

    public enum Kind {
        /** Per-attempt timeout or overall deadline exceeded. Retryable if budget remains. */
        TIMEOUT(true),
        /** Connection refused/reset, DNS, TLS. Retryable. */
        UNAVAILABLE(true),
        /** 5xx (except 501) or 429. Retryable. */
        SERVER_ERROR(true),
        /** Circuit breaker open: fail fast, do not retry. */
        CIRCUIT_OPEN(false),
        /** Bulkhead full: too many concurrent calls. */
        BULKHEAD_FULL(false),
        /** 404: a normal business outcome for lookups. */
        NOT_FOUND(false),
        /** Other 4xx: our request is wrong — a defect or contract mismatch, never retried. */
        CLIENT_ERROR(false),
        /** 2xx with a body that does not satisfy the contract (unparseable, missing fields). */
        CONTRACT_VIOLATION(false);

        private final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final Kind kind;
    private final String integration;
    private final int status;

    public IntegrationException(Kind kind, String integration, int status, String message, Throwable cause) {
        super(integration + ": " + kind + (status > 0 ? " (HTTP " + status + ")" : "") + " - " + message, cause);
        this.kind = kind;
        this.integration = integration;
        this.status = status;
    }

    public Kind kind() {
        return kind;
    }

    public String integration() {
        return integration;
    }

    public int status() {
        return status;
    }
}
