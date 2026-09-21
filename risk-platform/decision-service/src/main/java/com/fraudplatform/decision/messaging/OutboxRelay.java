package com.fraudplatform.decision.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes outbox rows to Kafka.
 *
 * <p>Each cycle claims a batch with {@code FOR UPDATE SKIP LOCKED} (safe with several instances), sends it
 * with the idempotent producer, waits for broker acknowledgements ({@code acks=all}) and marks the
 * acknowledged rows published in the same DB transaction that held the locks. A crash between "sent" and
 * "marked" re-sends the batch — hence at-least-once, and consumers de-duplicate on eventId.
 *
 * <p>Ordering: rows are sent in creation order and the idempotent producer preserves per-partition order.
 * If a row fails, later rows with the same key in the batch are not marked, so they are retried together.
 */
@Component
@ConditionalOnProperty(name = "platform.messaging.enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 200;

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Counter published;
    private final Counter failed;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong oldestAgeMs = new AtomicLong();

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, TransactionTemplate tx, ObjectMapper json,
                       MeterRegistry meters) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = tx;
        this.json = json;
        this.published = meters.counter("risk.outbox.published");
        this.failed = meters.counter("risk.outbox.failed");
        Gauge.builder("risk.outbox.backlog", backlog, AtomicLong::get).description("Unpublished outbox rows").register(meters);
        Gauge.builder("risk.outbox.oldest.age.seconds", oldestAgeMs, v -> v.get() / 1000.0)
                .description("Age of the oldest unpublished event (event publication lag)").register(meters);
    }

    @Scheduled(fixedDelayString = "${platform.messaging.relay-interval-ms:200}")
    public void relay() {
        try {
            Integer n;
            do {
                n = tx.execute(s -> publishBatch());
            } while (n != null && n == BATCH);
            OutboxRepository.Backlog b = outbox.backlog();
            backlog.set(b.count());
            oldestAgeMs.set(b.oldest() == null ? 0 : Duration.between(b.oldest(), Instant.now()).toMillis());
        } catch (RuntimeException e) {
            log.error("outbox relay cycle failed", e);
        }
    }

    private int publishBatch() {
        List<OutboxRepository.OutboxRow> rows = outbox.lockBatch(BATCH);
        if (rows.isEmpty()) return 0;
        List<CompletableFuture<?>> futures = new ArrayList<>(rows.size());
        for (OutboxRepository.OutboxRow row : rows) {
            ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.partitionKey(), row.payload());
            Map<String, String> headers = json.readValue(row.headers(), new TypeReference<>() {
            });
            headers.forEach((k, v) -> record.headers().add(new RecordHeader(k, v.getBytes(StandardCharsets.UTF_8))));
            futures.add(kafka.send(record));
        }
        List<UUID> ok = new ArrayList<>();
        Set<String> failedKeys = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            OutboxRepository.OutboxRow row = rows.get(i);
            String orderingKey = row.topic() + "|" + row.partitionKey();
            try {
                futures.get(i).get(10, TimeUnit.SECONDS);
                if (failedKeys.contains(orderingKey)) continue;   // keep per-key order: retry with the failed one
                ok.add(row.eventId());
            } catch (Exception e) {
                failedKeys.add(orderingKey);
                outbox.markFailed(row.eventId(), e.getClass().getSimpleName() + ": " + e.getMessage());
                failed.increment();
            }
        }
        outbox.markPublished(ok);
        published.increment(ok.size());
        if (!failedKeys.isEmpty()) log.warn("outbox: {} published, {} keys failed and will be retried", ok.size(), failedKeys.size());
        return rows.size();
    }
}
