package com.fraudplatform.decision.integration;

import com.fraudplatform.decision.domain.CustomerProfile;

import java.util.Optional;

/** Outbound port to the customer's profile API (implemented in Stage 5 with timeouts/retries/circuit breaker). */
public interface CustomerProfileClient {

    /** Empty when the customer is unknown <em>or</em> the service is unavailable (the caller degrades). */
    Optional<CustomerProfile> fetch(String tenantId, String customerId);

    /** Open connections before traffic arrives (no-op by default). */
    default void warmUp() {
    }
}
