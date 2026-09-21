package com.fraudplatform.fileadapter;

import com.fraudplatform.commons.events.EventType;
import com.fraudplatform.fileadapter.ingest.FileProcessor;
import com.fraudplatform.fileadapter.ingest.FileScanner;
import com.fraudplatform.fileadapter.publish.EventPublisher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FileAdapterIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:4.1.0");
    protected static final Path BASE;

    static {
        POSTGRES.start();
        KAFKA.start();
        // The decision-service owns the core schema and the reporting views the reconciliation reads.
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:../decision-service/src/main/resources/db/migration").load().migrate();
        try {
            BASE = Files.createTempDirectory("files");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final String HISTORY_HEADER = "transaction_id,event_time,customer_id,account_id,transaction_type,channel,amount,currency,"
            + "card_token,merchant_id,mcc,merchant_country,beneficiary_id,beneficiary_country,device_id,ip_address,ip_country";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("file-adapter.base-dir", BASE::toString);
        r.add("file-adapter.scan-interval-ms", () -> "3600000");
    }

    @Autowired
    protected FileScanner scanner;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    Environment env;

    // ------------------------------------------------------------------ helpers

    static Path write(String name, List<String> lines, Integer markerRecords) throws Exception {
        Path inbound = Files.createDirectories(BASE.resolve("inbound"));
        Path f = inbound.resolve(name);
        Files.writeString(f, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        boolean csv = name.endsWith(".csv");
        long records = markerRecords != null ? markerRecords : lines.size() - (csv ? 1 : 0);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
        Files.writeString(inbound.resolve(name + ".done"), "records=" + records + "\nsha256=" + sha + "\n");
        return f;
    }

    static String historyRow(String txId, String customer, String amount) {
        return txId + ",2026-04-30T10:15:00Z," + customer + "," + customer + "-ACC,CARD_PAYMENT,POS," + amount
                + ",EUR,tok_" + customer + ",M000001,5411,PT,,,,,";
    }

    FileProcessor.Result scanOne(String name) {
        return scanner.scan().stream().filter(r -> name.equals(r.report().get("file"))).findFirst().orElseThrow();
    }

    static List<JsonNode> consume(String topic, java.util.function.Predicate<JsonNode> match, int expected) {
        Properties p = new Properties();
        p.put("bootstrap.servers", KAFKA.getBootstrapServers());
        p.put("group.id", "it-" + UUID.randomUUID());
        p.put("auto.offset.reset", "earliest");
        p.put("key.deserializer", StringDeserializer.class.getName());
        p.put("value.deserializer", StringDeserializer.class.getName());
        List<JsonNode> out = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            c.subscribe(List.of(topic));
            long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (out.size() < expected && System.nanoTime() < end) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(300))) {
                    JsonNode n = JsonMapper.builder().build().readTree(r.value());
                    if (match.test(n)) out.add(n);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void validHistoryFileIsPublishedWithDeterministicEventIdsAndArchived() throws Exception {
        String name = "TXN_HISTORY_aldermoor-bank_20260430_001.csv";
        List<String> lines = new ArrayList<>(List.of(HISTORY_HEADER));
        for (int i = 0; i < 20; i++) lines.add(historyRow("H1-" + i, "ALD-C00010" + (i % 3), "12.50"));
        write(name, lines, null);

        FileProcessor.Result r = scanOne(name);
        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.report().get("eventsPublished")).isEqualTo(20);
        assertThat(Files.exists(BASE.resolve("archive/2026-04-30").resolve(name))).isTrue();
        assertThat(Files.exists(BASE.resolve("reports").resolve(name + ".report.json"))).isTrue();

        List<JsonNode> events = consume(EventType.Topics.TRANSACTIONS,
                e -> e.path("payload").path("transactionId").asString().startsWith("H1-"), 20);
        assertThat(events).hasSize(20);
        JsonNode first = events.stream().filter(e -> e.path("payload").path("transactionId").asString().equals("H1-0")).findFirst().orElseThrow();
        assertThat(first.get("payload").get("source").asString()).isEqualTo("BATCH");
        assertThat(first.get("eventId").asString())
                .isEqualTo(EventPublisher.eventId("aldermoor-bank", EventType.TransactionReceived, "H1-0").toString());
    }

    @Test
    void sameContentTwiceIsRejectedAsDuplicate() throws Exception {
        List<String> lines = List.of(HISTORY_HEADER, historyRow("DUP-1", "ALD-C000200", "5.00"));
        write("TXN_HISTORY_aldermoor-bank_20260430_002.csv", lines, null);
        assertThat(scanOne("TXN_HISTORY_aldermoor-bank_20260430_002.csv").status()).isEqualTo("COMPLETED");
        write("TXN_HISTORY_aldermoor-bank_20260430_003.csv", lines, null);   // same bytes, new name
        FileProcessor.Result dup = scanOne("TXN_HISTORY_aldermoor-bank_20260430_003.csv");
        assertThat(dup.status()).isEqualTo("REJECTED");
        assertThat(dup.reason()).isEqualTo("DUPLICATE_FILE");
        assertThat(Files.exists(BASE.resolve("rejected/TXN_HISTORY_aldermoor-bank_20260430_003.csv"))).isTrue();
    }

    @Test
    void schemaMismatchRejectsTheWholeFile() throws Exception {
        String name = "TXN_HISTORY_aldermoor-bank_20260430_010.csv";
        write(name, List.of(HISTORY_HEADER.replace("amount,currency", "currency,amount"), historyRow("SM-1", "ALD-C1", "1.00")), null);
        FileProcessor.Result r = scanOne(name);
        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.reason()).isEqualTo("SCHEMA_MISMATCH");
        assertThat(r.report()).containsKeys("expectedHeader", "actualHeader");
    }

    @Test
    void invalidRecordsAreQuarantinedAndValidOnesProcessed() throws Exception {
        String name = "TXN_HISTORY_aldermoor-bank_20260430_020.csv";
        List<String> lines = new ArrayList<>(List.of(HISTORY_HEADER));
        for (int i = 0; i < 98; i++) lines.add(historyRow("PQ-" + i, "ALD-C000300", "9.99"));
        lines.add(historyRow("PQ-bad-amount", "ALD-C000300", "-5"));                     // line 100
        lines.add(historyRow("PQ-pan", "ALD-C000300", "7.00").replace("tok_ALD-C000300", "4111111111111111"));
        write(name, lines, null);
        FileProcessor.Result r = scanOne(name);
        assertThat(r.status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(r.report().get("eventsPublished")).isEqualTo(98);
        List<String> errors = jdbc.sql("SELECT errors::text FROM ingestion.quarantined_records WHERE run_id = ? ORDER BY line_number")
                .param(r.runId()).query(String.class).list();
        assertThat(errors).hasSize(2);
        assertThat(errors.get(0)).contains("amount: must be positive");
        assertThat(errors.get(1)).contains("raw PAN");
        assertThat(Files.exists(BASE.resolve("quarantine").resolve(name + ".quarantine.jsonl"))).isTrue();
    }

    @Test
    void tooManyInvalidRecordsRejectTheFile() throws Exception {
        String name = "TXN_HISTORY_aldermoor-bank_20260430_030.csv";
        List<String> lines = new ArrayList<>(List.of(HISTORY_HEADER));
        for (int i = 0; i < 10; i++) lines.add(historyRow("TM-" + i, "ALD-C000400", i < 3 ? "abc" : "1.00"));
        write(name, lines, null);
        FileProcessor.Result r = scanOne(name);
        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.reason()).isEqualTo("TOO_MANY_INVALID_RECORDS");
        assertThat(r.report().get("eventsPublished")).isEqualTo(0);
    }

    @Test
    void incompleteTransfersAreDetected() throws Exception {
        String name = "TXN_HISTORY_aldermoor-bank_20260430_040.csv";
        write(name, List.of(HISTORY_HEADER, historyRow("IC-1", "ALD-C1", "1.00"), historyRow("IC-2", "ALD-C1", "1.00")), 5);
        FileProcessor.Result r = scanOne(name);
        assertThat(r.reason()).isEqualTo("FILE_INCOMPLETE");

        // No marker yet: the file is left alone until the sender finishes.
        Path f = BASE.resolve("inbound/TXN_HISTORY_aldermoor-bank_20260430_041.csv");
        Files.writeString(f, HISTORY_HEADER + "\n" + historyRow("IC-3", "ALD-C1", "1.00") + "\n");
        assertThat(scanner.scan()).noneMatch(x -> f.getFileName().toString().equals(x.report().get("file")));
        assertThat(Files.exists(f)).isTrue();
        Files.delete(f);
    }

    @Test
    void recordsAlreadyIngestedByAnotherFileAreSkipped() throws Exception {
        write("TXN_HISTORY_aldermoor-bank_20260430_050.csv", List.of(HISTORY_HEADER, historyRow("RD-1", "ALD-C5", "3.00")), null);
        scanOne("TXN_HISTORY_aldermoor-bank_20260430_050.csv");
        write("TXN_HISTORY_aldermoor-bank_20260430_051.csv",
                List.of(HISTORY_HEADER, historyRow("RD-1", "ALD-C5", "3.00"), historyRow("RD-2", "ALD-C5", "4.00")), null);
        FileProcessor.Result r = scanOne("TXN_HISTORY_aldermoor-bank_20260430_051.csv");
        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.report().get("eventsPublished")).isEqualTo(1);
    }

    @Test
    void chargebacksAndLabelsBecomeFraudConfirmedEvents() throws Exception {
        write("CHARGEBACKS_aldermoor-bank_20260430_001.csv", List.of(
                "transaction_id,chargeback_date,reason_code,amount,currency,customer_id",
                "CB-1,2026-04-30,4837,120.00,EUR,ALD-C000777"), null);
        assertThat(scanOne("CHARGEBACKS_aldermoor-bank_20260430_001.csv").status()).isEqualTo("COMPLETED");
        write("FRAUD_LABELS_aldermoor-bank_20260430_001.jsonl", List.of(
                "{\"transactionId\":\"LB-1\",\"customerId\":\"ALD-C000778\",\"label\":\"GENUINE\",\"fraudType\":null,\"reportedAt\":\"2026-04-30T09:00:00Z\",\"source\":\"CUSTOMER_REPORT\"}",
                "{not json"), null);
        FileProcessor.Result labels = scanOne("FRAUD_LABELS_aldermoor-bank_20260430_001.jsonl");
        assertThat(labels.status()).isEqualTo("REJECTED");   // 50% invalid > 5% threshold
        write("FRAUD_LABELS_aldermoor-bank_20260430_002.jsonl", List.of(
                "{\"transactionId\":\"LB-1\",\"customerId\":\"ALD-C000778\",\"label\":\"GENUINE\",\"fraudType\":null,\"reportedAt\":\"2026-04-30T09:00:00Z\",\"source\":\"CUSTOMER_REPORT\"}"), null);
        assertThat(scanOne("FRAUD_LABELS_aldermoor-bank_20260430_002.jsonl").status()).isEqualTo("COMPLETED");

        List<JsonNode> events = consume(EventType.Topics.LABELS,
                e -> List.of("CB-1", "LB-1").contains(e.path("payload").path("transactionId").asString()), 2);
        assertThat(events).extracting(e -> e.path("payload").path("source").asString())
                .containsExactlyInAnyOrder("CHARGEBACK", "CUSTOMER_REPORT");
    }

    @Test
    void customerProfilesArePublished() throws Exception {
        write("CUSTOMER_PROFILES_quillon-pay_20260430_001.jsonl", List.of(
                "{\"customerId\":\"QPY-C9\",\"segment\":\"frequent\",\"homeCountry\":\"ES\",\"tenureDays\":\"400\",\"avgAmount90d\":\"31.50\",\"riskTier\":\"standard\",\"boundDeviceIds\":[\"D1\",\"D2\"]}"), null);
        assertThat(scanOne("CUSTOMER_PROFILES_quillon-pay_20260430_001.jsonl").status()).isEqualTo("COMPLETED");
        List<JsonNode> events = consume(EventType.Topics.CUSTOMERS, e -> "QPY-C9".equals(e.path("payload").path("customerId").asString()), 1);
        assertThat(events.getFirst().path("payload").path("boundDeviceIds").size()).isEqualTo(2);
    }

    @Test
    void settlementIsReconciledAgainstPlatformDecisions() throws Exception {
        insertDecision("RC-MATCH", "APPROVE", "50.00");
        insertDecision("RC-MISMATCH", "APPROVE", "50.00");
        insertDecision("RC-DECLINED", "DECLINE", "900.00");
        insertDecision("RC-NOSETTLE", "APPROVE", "15.00");
        write("SETTLEMENT_aldermoor-bank_20260430_001.csv", List.of(
                "transaction_id,settlement_date,settled_amount,currency,status",
                "RC-MATCH,2026-04-30,50.00,EUR,SETTLED",
                "RC-MISMATCH,2026-04-30,55.00,EUR,SETTLED",
                "RC-DECLINED,2026-04-30,900.00,EUR,SETTLED",
                "RC-UNKNOWN,2026-04-30,20.00,EUR,SETTLED"), null);
        FileProcessor.Result r = scanOne("SETTLEMENT_aldermoor-bank_20260430_001.csv");
        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.report().get("reconciliation").toString())
                .contains("MATCHED=1").contains("AMOUNT_MISMATCH=1").contains("SETTLED_BUT_DECLINED=1")
                .contains("NOT_SCORED=1").contains("APPROVED_NOT_SETTLED=1");
        assertThat(Files.readString(BASE.resolve("outbound/RECON_aldermoor-bank_20260430.csv"))).contains("RC-DECLINED,SETTLED_BUT_DECLINED");

        String api = RestClient.create("http://localhost:" + env.getProperty("local.server.port"))
                .get().uri("/v1/reconciliation/aldermoor-bank/2026-04-30?category=SETTLED_BUT_DECLINED")
                .header("X-Api-Key", "dev-file-ops-key").retrieve().body(String.class);
        assertThat(api).contains("RC-DECLINED");
    }

    @Test
    void opsApiRequiresTheKey() {
        var res = RestClient.builder().baseUrl("http://localhost:" + env.getProperty("local.server.port"))
                .defaultStatusHandler(s -> true, (req, resp) -> { }).build()
                .get().uri("/v1/ingestion/runs").retrieve().toEntity(String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    private void insertDecision(String txId, String decision, String amount) {
        Timestamp t = Timestamp.from(Instant.parse("2026-04-30T12:00:00Z"));
        jdbc.sql("""
                        INSERT INTO public.transactions (tenant_id, transaction_id, customer_id, account_id, event_time, transaction_type,
                                                         channel, amount, currency, card_token, merchant_id)
                        VALUES ('aldermoor-bank', ?, 'ALD-C1', 'ALD-A1', ?, 'CARD_PAYMENT', 'ECOM', ?::numeric, 'EUR', 'tok_1', 'M1')
                        ON CONFLICT DO NOTHING
                        """).params(txId, t, amount).update();
        jdbc.sql("""
                        INSERT INTO public.risk_decisions (decision_id, tenant_id, transaction_id, decision, risk_score, risk_level,
                                                           reasons, feature_vector, strategy_version, feature_spec_version, processing_ms,
                                                           client_id, created_at)
                        VALUES (?, 'aldermoor-bank', ?, ?, 0.1, 'LOW', '[]', '{}', '1.1.0', 'fs-1.0', 5, 'test', ?)
                        ON CONFLICT DO NOTHING
                        """).params(UUID.randomUUID(), txId, decision, t).update();
    }

    // ------------------------------------------------------------------ workbench-generated samples

    static final Path SAMPLES = Path.of("").toAbsolutePath().getParent().getParent().resolve("data/samples/files");

    private java.util.Map<String, FileProcessor.Result> runSamples(Path dir, String sequenceOverride) throws Exception {
        Path inbound = Files.createDirectories(BASE.resolve("inbound"));
        java.util.List<String> names = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String name = p.getFileName().toString();
                if (sequenceOverride != null) name = name.replace("_001.", "_" + sequenceOverride + ".");
                Files.copy(p, inbound.resolve(name));
                names.add(name.replace(".done", ""));
            }
        }
        return scanner.scan().stream().filter(r -> names.contains((String) r.report().get("file")))
                .collect(java.util.stream.Collectors.toMap(r -> (String) r.report().get("file"), r -> r, (a, b) -> b));
    }

    /** Contract check: every good sample produced by the workbench is accepted. */
    @Test
    void goodSampleFilesAreAccepted() throws Exception {
        for (String tenant : new String[]{"aldermoor-bank", "quillon-pay"}) {
            var results = runSamples(SAMPLES.resolve(tenant), "701");
            assertThat(results).hasSize(4);
            results.forEach((file, r) -> assertThat(r.status()).as(file + " " + r.report()).isEqualTo("COMPLETED"));
        }
    }

    /** Each deliberately broken sample (troubleshooting lab) is rejected for the documented reason. */
    @Test
    void brokenSampleFilesAreRejectedForTheRightReason() throws Exception {
        var results = runSamples(SAMPLES.resolve("troubleshooting/aldermoor-bank"), null);
        var reasons = results.values().stream().collect(java.util.stream.Collectors.toMap(
                r -> ((String) r.report().get("file")).replaceAll(".*_(\\d{3})\\.csv$", "$1"), r -> String.valueOf(r.reason())));
        assertThat(reasons).containsEntry("901", "SCHEMA_MISMATCH").containsEntry("902", "TOO_MANY_INVALID_RECORDS")
                .containsEntry("903", "FILE_INCOMPLETE");
        assertThat(results.values()).anyMatch(r -> "UNKNOWN_FILE_NAME".equals(r.reason()));
    }
}
