package com.fraudplatform.decision.messaging;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Dead-letter topic operations for support engineers.
 * <ul>
 *   <li><b>peek</b>: read DLT records without consuming them (no consumer group), with the failure reason.</li>
 *   <li><b>redrive</b>: republish DLT records to their original topic after the root cause is fixed.
 *       Uses a dedicated consumer group so each DLT record is redriven once; consumers de-duplicate on
 *       eventId, so redriving an already-processed event is harmless.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "platform.messaging.enabled", havingValue = "true")
public class DltOperations {

    private static final Logger log = LoggerFactory.getLogger(DltOperations.class);

    public record DltRecord(int partition, long offset, String key, String originalTopic, String exception,
                            String exceptionMessage, String value) {
    }

    private final ConsumerFactory<String, String> consumers;
    private final KafkaTemplate<String, String> kafka;

    public DltOperations(ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> kafka) {
        this.consumers = consumers;
        this.kafka = kafka;
    }

    public List<DltRecord> peek(String dltTopic, int max) {
        Properties p = new Properties();
        p.put("enable.auto.commit", "false");
        try (Consumer<String, String> c = consumers.createConsumer("dlt-peek-" + UUID.randomUUID(), "", null, p)) {
            List<TopicPartition> parts = c.partitionsFor(dltTopic).stream()
                    .map(pi -> new TopicPartition(dltTopic, pi.partition())).toList();
            c.assign(parts);
            c.seekToBeginning(parts);
            Map<TopicPartition, Long> end = c.endOffsets(parts);
            List<DltRecord> out = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (out.size() < max && System.nanoTime() < deadline && !reachedEnd(c, end)) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(200))) {
                    out.add(new DltRecord(r.partition(), r.offset(), r.key(), header(r, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                            header(r, KafkaHeaders.DLT_EXCEPTION_FQCN), header(r, KafkaHeaders.DLT_EXCEPTION_MESSAGE), r.value()));
                    if (out.size() >= max) break;
                }
            }
            return out;
        }
    }

    public Map<String, Object> redrive(String dltTopic, int max) {
        int redriven = 0;
        Map<String, Object> result = new HashMap<>();
        try (Consumer<String, String> c = consumers.createConsumer("dlt-redrive", "", null, props())) {
            c.subscribe(List.of(dltTopic));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (redriven < max && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> batch = c.poll(Duration.ofMillis(300));
                if (batch.isEmpty() && redriven > 0) break;
                Map<TopicPartition, OffsetAndMetadata> commit = new HashMap<>();
                for (ConsumerRecord<String, String> r : batch) {
                    String original = header(r, KafkaHeaders.DLT_ORIGINAL_TOPIC);
                    if (original == null) continue;
                    ProducerRecord<String, String> out = new ProducerRecord<>(original, r.key(), r.value());
                    for (Header h : r.headers()) {
                        if (!h.key().startsWith("kafka_dlt")) out.headers().add(h);
                    }
                    out.headers().add(new RecordHeader("x-redriven-from", dltTopic.getBytes(StandardCharsets.UTF_8)));
                    kafka.send(out).get(10, TimeUnit.SECONDS);
                    commit.put(new TopicPartition(r.topic(), r.partition()), new OffsetAndMetadata(r.offset() + 1));
                    redriven++;
                }
                if (!commit.isEmpty()) c.commitSync(commit);
            }
        } catch (Exception e) {
            log.error("DLT redrive failed after {} records", redriven, e);
            result.put("error", e.toString());
        }
        log.warn("DLT redrive topic={} records={}", dltTopic, redriven);
        result.put("topic", dltTopic);
        result.put("redriven", redriven);
        return result;
    }

    private static Properties props() {
        Properties p = new Properties();
        p.put("enable.auto.commit", "false");
        p.put("auto.offset.reset", "earliest");
        return p;
    }

    private static boolean reachedEnd(Consumer<String, String> c, Map<TopicPartition, Long> end) {
        return end.entrySet().stream().allMatch(e -> c.position(e.getKey()) >= e.getValue());
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
