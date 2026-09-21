package com.fraudplatform.commons.correlation;

/**
 * Correlation conventions shared by every service, outbound client and event producer.
 * A correlation ID is accepted from the caller (or generated), put in the logging MDC,
 * returned in the response, propagated on outbound HTTP calls and copied into event headers.
 */
public final class Correlation {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_TENANT_ID = "tenantId";
    public static final String MDC_CLIENT_ID = "clientId";
    public static final String MDC_TRANSACTION_ID = "transactionId";

    /** Upper bound so a caller cannot inject arbitrarily long values into logs. */
    public static final int MAX_LENGTH = 64;

    private Correlation() {
    }

    public static boolean isAcceptable(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_LENGTH
                && value.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.');
    }
}
