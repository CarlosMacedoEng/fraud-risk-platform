package com.fraudplatform.decision.messaging;

import com.fraudplatform.commons.events.EventType;
import com.fraudplatform.decision.IntegrationTestBase;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/** Transactional outbox → Kafka → idempotent consumers → DLT → redrive, against a real broker. */
class MessagingIntegrationTest extends IntegrationTestBase {

    static final ObjectMapper JSON = JsonMapper.builder().build();
    static final String CASE_DLT = EventType.Topics.deadLetter(EventType.Topics.DECISIONS, MessagingConfig.CASE_CREATOR);

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:4.1.0");
    static final WireMockServer CASES = new WireMockServer(options().dynamicPort());

    static {
        KAFKA.start();
        CASES.start();
    }

    @DynamicPropertySource
    static void messaging(DynamicPropertyRegistry r) {
        r.add("platform.messaging.enabled", () -> "true");
        r.add("platform.messaging.relay-interval-ms", () -> "100");
        r.add("platform.integrations.endpoints.case-management.enabled", () -> "true");
        r.add("platform.integrations.endpoints.case-management.base-url", CASES::baseUrl);
        r.add("platform.integrations.endpoints.case-management.initial-backoff-ms", () -> "20");
        r.add("platform.integrations.endpoints.case-management.deadline-ms", () -> "1000");
        r.add("platform.cases.reconcile-ms", () -> "3600000");   // keep the reconciliation job out of these tests
    }

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void stubs() {
        CASES.resetAll();
        CASES.stubFor(post("/cases/v1/cases").willReturn(okJson("{\"caseReference\":\"CM-1\",\"status\":\"OPEN\"}").withStatus(201)));
    }

    // ------------------------------------------------------------------ helpers

    static void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    static List<JsonNode> consume(String topic, Predicate<JsonNode> match, int expected, Duration timeout) {
        Properties p = new Properties();
        p.put("bootstrap.servers", KAFKA.getBootstrapServers());
        p.put("group.id", "it-" + UUID.randomUUID());
        p.put("auto.offset.reset", "earliest");
        p.put("key.deserializer", StringDeserializer.class.getName());
        p.put("value.deserializer", StringDeserializer.class.getName());
        List<JsonNode> found = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            c.subscribe(List.of(topic));
            long end = System.nanoTime() + timeout.toNanos();
            while (found.size() < expected && System.nanoTime() < end) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(300))) {
                    try {
                        JsonNode n = JSON.readTree(r.value());
                        if (match.test(n)) found.add(n);
                    } catch (RuntimeException notJson) {
                        // poison messages from other tests
                    }
                }
            }
        }
        return found;
    }

    static void produce(String topic, String key, String value) throws Exception {
        Properties p = new Properties();
        p.put("bootstrap.servers", KAFKA.getBootstrapServers());
        p.put("key.serializer", StringSerializer.class.getName());
        p.put("value.serializer", StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        }
    }

    /** First transfer ≥ 5,000 to a new beneficiary: policy BEN-003 guarantees REVIEW in strategy 1.1.0. */
    private JsonNode scoreReviewTransfer() throws Exception {
        String tx = "MSG-" + UUID.randomUUID();
        var res = http.post().uri("/v1/decisions").header("X-Api-Key", GATEWAY_KEY).header("Idempotency-Key", tx)
                .body(transfer(tx, "ALD-C000001", 6000, "D-KNOWN-1", "B-" + UUID.randomUUID(),
                        Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()))
                .retrieve().toEntity(String.class);
        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(200);
        JsonNode d = JSON.readTree(res.getBody());
        assertThat(d.get("decision").asString()).isNotEqualTo("APPROVE");
        return d;
    }

    private String caseStatus(String decisionId) {
        return jdbc.sql("SELECT status FROM fraud_cases WHERE decision_id = ?::uuid").param(decisionId)
                .query(String.class).optional().orElse(null);
    }

    private List<DltOperations.DltRecord> dlt() throws Exception {
        String body = http.get().uri("/v1/admin/tenants/aldermoor-bank/events/dlt?topic=" + CASE_DLT)
                .header("X-Api-Key", ADMIN_KEY).retrieve().body(String.class);
        return JSON.readValue(body, JSON.getTypeFactory().constructCollectionType(List.class, DltOperations.DltRecord.class));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void decisionEventsArePublishedThroughTheOutboxAndMatchTheSchemas() throws Exception {
        JsonNode d = scoreReviewTransfer();
        String decisionId = d.get("decisionId").asString();
        List<JsonNode> decisions = consume(EventType.Topics.DECISIONS,
                e -> decisionId.equals(e.path("payload").path("decisionId").asString()), 1, Duration.ofSeconds(20));
        JsonNode created = decisions.stream().filter(e -> e.get("eventType").asString().equals("RiskDecisionCreated"))
                .findFirst().orElseThrow();
        assertThat(EventSchemas.validate(created)).isEmpty();
        assertThat(created.get("partitionKey").asString()).isEqualTo("ALD-C000001");
        assertThat(created.get("correlationId").asString()).isEqualTo(d.get("correlationId").asString());

        List<JsonNode> received = consume(EventType.Topics.TRANSACTIONS,
                e -> d.get("transactionId").asString().equals(e.path("payload").path("transactionId").asString()), 1,
                Duration.ofSeconds(20));
        assertThat(received).hasSize(1);
        assertThat(EventSchemas.validate(received.getFirst())).isEmpty();
        assertThat(received.getFirst().toString()).doesNotContain("tok_");   // no card tokens in broadcast events

        await("outbox rows published", Duration.ofSeconds(10), () -> jdbc.sql(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id IN (?, ?) AND published_at IS NULL")
                .params(decisionId, d.get("transactionId").asString()).query(Integer.class).single() == 0);
    }

    @Test
    void reviewDecisionOpensExactlyOneCaseEvenWhenTheEventIsDeliveredTwice() throws Exception {
        JsonNode d = scoreReviewTransfer();
        String decisionId = d.get("decisionId").asString();
        await("case opened by the case-creator consumer", Duration.ofSeconds(20), () -> "OPEN".equals(caseStatus(decisionId)));

        // Re-deliver the same RiskDecisionCreated event (at-least-once in action).
        JsonNode created = consume(EventType.Topics.DECISIONS,
                e -> decisionId.equals(e.path("payload").path("decisionId").asString())
                        && e.get("eventType").asString().equals("RiskDecisionCreated"), 1, Duration.ofSeconds(10)).getFirst();
        produce(EventType.Topics.DECISIONS, "ALD-C000001", created.toString());
        Thread.sleep(2000);
        CASES.verify(1, postRequestedFor(urlEqualTo("/cases/v1/cases")));
        assertThat(jdbc.sql("SELECT count(*) FROM fraud_cases WHERE decision_id = ?::uuid").param(decisionId)
                .query(Integer.class).single()).isEqualTo(1);

        List<JsonNode> caseEvents = consume(EventType.Topics.CASES,
                e -> decisionId.equals(e.path("payload").path("decisionId").asString()), 1, Duration.ofSeconds(20));
        assertThat(caseEvents).hasSize(1);
        assertThat(EventSchemas.validate(caseEvents.getFirst())).isEmpty();
    }

    @Test
    void poisonMessageGoesToTheDeadLetterTopic() throws Exception {
        String marker = "poison-" + UUID.randomUUID();
        produce(EventType.Topics.DECISIONS, marker, "{not json " + marker);
        await("poison record in DLT", Duration.ofSeconds(20), () -> {
            try {
                return dlt().stream().anyMatch(r -> marker.equals(r.key()));
            } catch (Exception e) {
                return false;
            }
        });
        var record = dlt().stream().filter(r -> marker.equals(r.key())).findFirst().orElseThrow();
        assertThat(record.originalTopic()).isEqualTo(EventType.Topics.DECISIONS);
        assertThat(record.exceptionMessage()).isNotBlank();
    }

    @Test
    void permanentDownstreamRejectionGoesToDltAndCanBeRedrivenAfterTheFix() throws Exception {
        CASES.stubFor(post("/cases/v1/cases").willReturn(aResponse().withStatus(400).withBody("{\"error\":\"unknown priority\"}")));
        JsonNode d = scoreReviewTransfer();
        String decisionId = d.get("decisionId").asString();
        await("rejected event in DLT", Duration.ofSeconds(20), () -> {
            try {
                return dlt().stream().anyMatch(r -> r.value() != null && r.value().contains(decisionId));
            } catch (Exception e) {
                return false;
            }
        });
        assertThat(caseStatus(decisionId)).isEqualTo("PENDING_EXTERNAL");
        CASES.verify(1, postRequestedFor(urlEqualTo("/cases/v1/cases")));   // 4xx: not retried

        // Root cause fixed on the customer side → redrive.
        CASES.resetAll();
        CASES.stubFor(post("/cases/v1/cases").willReturn(okJson("{\"caseReference\":\"CM-2\",\"status\":\"OPEN\"}").withStatus(201)));
        String result = http.post().uri("/v1/admin/tenants/aldermoor-bank/events/dlt/redrive?topic=" + CASE_DLT)
                .header("X-Api-Key", ADMIN_KEY).retrieve().body(String.class);
        assertThat(JSON.readTree(result).get("redriven").asInt()).isPositive();
        await("case opened after redrive", Duration.ofSeconds(20), () -> "OPEN".equals(caseStatus(decisionId)));
    }

    @Test
    void replayRepublishesEventsAndConsumersStayIdempotent() throws Exception {
        JsonNode d = scoreReviewTransfer();
        String decisionId = d.get("decisionId").asString();
        await("case opened", Duration.ofSeconds(20), () -> "OPEN".equals(caseStatus(decisionId)));
        int callsBefore = CASES.findAll(postRequestedFor(urlEqualTo("/cases/v1/cases"))).size();

        Instant from = Instant.now().minusSeconds(60);
        String res = http.post().uri("/v1/admin/tenants/aldermoor-bank/events/replay").header("X-Api-Key", ADMIN_KEY)
                .body("{\"eventType\":\"RiskDecisionCreated\",\"from\":\"" + from + "\",\"to\":\"" + Instant.now().plusSeconds(1) + "\"}")
                .retrieve().body(String.class);
        assertThat(JSON.readTree(res).get("requeued").asInt()).isPositive();
        await("replayed events published", Duration.ofSeconds(10), () -> jdbc.sql(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NULL").query(Integer.class).single() == 0);
        Thread.sleep(2000);
        assertThat(CASES.findAll(postRequestedFor(urlEqualTo("/cases/v1/cases")))).hasSize(callsBefore);
    }

    @Test
    void configurationChangesArePublished() throws Exception {
        String res = http.post().uri("/v1/admin/tenants/aldermoor-bank/models/aldermoor-bank-lgbm-1.1.0/register")
                .header("X-Api-Key", ADMIN_KEY).retrieve().body(String.class);
        assertThat(res).contains("aldermoor-bank-lgbm-1.1.0");
        http.post().uri("/v1/admin/tenants/aldermoor-bank/models/aldermoor-bank-lgbm-1.1.0/status").header("X-Api-Key", ADMIN_KEY)
                .body("{\"status\":\"SHADOW\",\"reason\":\"messaging test\"}").retrieve().toBodilessEntity();
        List<JsonNode> events = consume(EventType.Topics.CONFIG,
                e -> "ModelVersionPromoted".equals(e.path("eventType").asString())
                        && "aldermoor-bank-lgbm-1.1.0".equals(e.path("payload").path("modelVersion").asString()), 1, Duration.ofSeconds(20));
        assertThat(events).hasSize(1);
        assertThat(EventSchemas.validate(events.getFirst())).isEmpty();
    }
}
