package com.fraudplatform.commons.error;

/**
 * Stable, documented error codes. HTTP status is part of the contract: clients may branch on
 * {@code code}; the human-readable message may change between versions.
 */
public enum ErrorCode {
    VALIDATION_FAILED(400, "Request failed validation"),
    MISSING_IDEMPOTENCY_KEY(400, "Idempotency-Key header is required"),
    UNAUTHENTICATED(401, "Missing or invalid API key"),
    FORBIDDEN(403, "Client is not allowed to perform this operation"),
    NOT_FOUND(404, "Resource not found"),
    IDEMPOTENCY_IN_PROGRESS(409, "A request with this Idempotency-Key is still being processed"),
    DUPLICATE_TRANSACTION(409, "Transaction was already scored with a different Idempotency-Key"),
    VERSION_CONFLICT(409, "Resource was modified concurrently"),
    IDEMPOTENCY_KEY_REUSED(422, "Idempotency-Key was reused with a different request body"),
    STRATEGY_INVALID(422, "Strategy configuration failed validation"),
    INVALID_STATE_TRANSITION(422, "Operation not allowed in the current state"),
    UNKNOWN_TENANT(422, "Tenant is not configured"),
    DEPENDENCY_UNAVAILABLE(503, "A required dependency is unavailable"),
    INTERNAL_ERROR(500, "Unexpected error");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
