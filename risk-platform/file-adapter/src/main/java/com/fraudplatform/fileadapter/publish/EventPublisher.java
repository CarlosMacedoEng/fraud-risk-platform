package com.fraudplatform.fileadapter.publish;

import com.fraudplatform.commons.events.EventEnvelope;
import com.fraudplatform.commons.events.EventType;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publishes file records as domain events.
 *
 * <p>Why no outbox here: the file itself is the durable, replayable source. Event IDs are
 * <b>deterministic</b> ({@code UUID.nameUUIDFromBytes(tenant|type|naturalKey)}), so re-processing a file
 * after a crash or publish failure produces the <em>same</em> event IDs and consumers de-duplicate them.
 */
@Component
public class EventPublisher {

    public record Event(EventType type, String tenant, String partitionKey, String naturalKey, Map<String, Object> payload) {
    }

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public static UUID eventId(String tenant, EventType type, String naturalKey) {
        return UUID.nameUUIDFromBytes((tenant + "|" + type.name() + "|" + naturalKey).getBytes(StandardCharsets.UTF_8));
    }

    /** Sends all events and waits for broker acknowledgement; throws if any event was not acknowledged. */
    public int publishAll(List<Event> events, String correlationId) throws Exception {
        List<CompletableFuture<?>> futures = new ArrayList<>(events.size());
        for (Event e : events) {
            UUID id = eventId(e.tenant(), e.type(), e.naturalKey());
            EventEnvelope env = new EventEnvelope(id, e.type().name(), e.type().version(), Instant.now(), e.tenant(),
                    e.partitionKey(), correlationId, "file-adapter", e.payload());
            ProducerRecord<String, String> r = new ProducerRecord<>(e.type().topic(), e.partitionKey(), json.writeValueAsString(env));
            r.headers().add(new RecordHeader("eventId", id.toString().getBytes(StandardCharsets.UTF_8)));
            r.headers().add(new RecordHeader("eventType", e.type().name().getBytes(StandardCharsets.UTF_8)));
            r.headers().add(new RecordHeader("tenantId", e.tenant().getBytes(StandardCharsets.UTF_8)));
            if (correlationId != null) r.headers().add(new RecordHeader("X-Correlation-Id", correlationId.getBytes(StandardCharsets.UTF_8)));
            futures.add(kafka.send(r));
        }
        for (CompletableFuture<?> f : futures) f.get(30, TimeUnit.SECONDS);
        return events.size();
    }
}
