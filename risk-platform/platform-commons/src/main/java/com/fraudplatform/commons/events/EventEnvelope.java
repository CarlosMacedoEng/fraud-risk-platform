package com.fraudplatform.commons.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Envelope shared by every domain event (schema: docs/events/envelope.v1.schema.json).
 *
 * <p>Delivery is at-least-once: consumers MUST de-duplicate on {@link #eventId()} and MUST ignore unknown
 * fields. Ordering is guaranteed only per {@link #partitionKey()} (Kafka partition).
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String tenantId,
        String partitionKey,
        String correlationId,
        String producer,
        Map<String, Object> payload) {
}
