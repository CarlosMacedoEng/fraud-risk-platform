package com.fraudplatform.fileadapter.ingest;

import com.fraudplatform.commons.events.EventType;
import com.fraudplatform.fileadapter.config.FileAdapterProperties;
import com.fraudplatform.fileadapter.persistence.RunRepository;
import com.fraudplatform.fileadapter.publish.EventPublisher;
import com.fraudplatform.fileadapter.recon.ReconciliationService;
import com.fraudplatform.fileadapter.spec.FileName;
import com.fraudplatform.fileadapter.spec.FileSpec;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The ingestion pipeline for one file.
 *
 * <ol>
 *   <li>Name contract → spec, tenant, business date, sequence (unknown names are rejected).</li>
 *   <li>Completeness: {@code .done} marker record count and SHA-256 must match the data file.</li>
 *   <li>Duplicate file: same content already processed (SHA-256) or same name already completed → rejected.</li>
 *   <li>Claim (multi-instance safe), move to {@code processing/}.</li>
 *   <li>Schema: CSV header must match the agreed spec exactly → otherwise the whole file is rejected.</li>
 *   <li>Records: field validation; invalid → quarantined with line number and reasons; duplicates within the
 *       file or already ingested → counted and skipped.</li>
 *   <li>Error-ratio policy: above the spec's threshold the file is rejected (systemic problem); below it,
 *       valid records are processed (partial success).</li>
 *   <li>Publish events (deterministic IDs) or reconcile (settlement); record ingested keys.</li>
 *   <li>Report (JSON) + quarantine file, archive the source, metrics.</li>
 * </ol>
 */
@Component
public class FileProcessor {

    private static final Logger log = LoggerFactory.getLogger(FileProcessor.class);

    public record Result(UUID runId, String status, String reason, Map<String, Object> report) {
    }

    private final FileAdapterProperties props;
    private final RunRepository runs;
    private final EventPublisher publisher;
    private final ReconciliationService reconciliation;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    public FileProcessor(FileAdapterProperties props, RunRepository runs, EventPublisher publisher,
                         ReconciliationService reconciliation, ObjectMapper json, MeterRegistry meters) {
        this.props = props;
        this.runs = runs;
        this.publisher = publisher;
        this.reconciliation = reconciliation;
        this.json = json;
        this.meters = meters;
    }

    public Result process(Path file) throws IOException {
        long start = System.nanoTime();
        UUID runId = UUID.randomUUID();
        String name = file.getFileName().toString();
        MDC.put("correlationId", "file-" + runId);
        try {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("runId", runId.toString());
            report.put("file", name);

            var parsed = FileName.parse(name);
            String sha = sha256(file);
            report.put("sha256", sha);
            if (parsed.isEmpty()) {
                return reject(file, runId, sha, "UNKNOWN_FILE_NAME", null, report, start,
                        "expected <TYPE>_<tenant>_<yyyyMMdd>_<seq>.<csv|jsonl>");
            }
            FileName fn = parsed.get();
            report.put("tenant", fn.tenantId());
            report.put("fileType", fn.spec().name());
            report.put("sourceSystem", fn.spec().sourceSystem());
            report.put("businessDate", fn.businessDate().toString());
            if (!props.tenants().contains(fn.tenantId())) {
                return reject(file, runId, sha, "UNKNOWN_TENANT", fn, report, start, fn.tenantId());
            }

            var marker = DoneMarker.read(file);
            if (marker.isEmpty()) return null;   // sender still writing: try again on the next scan
            List<RecordReader.Line> lines;
            try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                lines = RecordReader.read(in, fn.spec(), json);
            } catch (RecordReader.SchemaMismatchException e) {
                report.put("expectedHeader", e.expected);
                report.put("actualHeader", e.actual);
                return reject(file, runId, sha, "SCHEMA_MISMATCH", fn, report, start, e.getMessage());
            }
            if (marker.get().records() != lines.size() || (marker.get().sha256() != null && !marker.get().sha256().equalsIgnoreCase(sha))) {
                report.put("markerRecords", marker.get().records());
                report.put("actualRecords", lines.size());
                return reject(file, runId, sha, "FILE_INCOMPLETE", fn, report, start,
                        "done marker does not match file (records or checksum) — truncated or corrupted in transit");
            }

            var existing = runs.findActiveBySha(sha);
            if (existing.isPresent()) {
                if ("PROCESSING".equals(existing.get().status()) && runs.takeOverStale(existing.get().runId(), props.staleRunMinutes())) {
                    runId = existing.get().runId();
                    log.warn("taking over stale run {} for {}", runId, name);
                } else {
                    report.put("previousRunId", existing.get().runId().toString());
                    return reject(file, runId, sha, "DUPLICATE_FILE", fn, report, start, "identical content already processed");
                }
            } else if (runs.identityAlreadyCompleted(fn.tenantId(), fn.spec().name(), fn.businessDate(), fn.sequence())) {
                return reject(file, runId, sha, "DUPLICATE_FILE_NAME", fn, report, start,
                        "a different file with this name was already processed; resend corrections with a new sequence number");
            } else if (!runs.claim(runId, fn.tenantId(), fn.spec().name(), name, fn.businessDate(), fn.sequence(), sha)) {
                return null;   // another instance claimed it a moment ago
            }
            report.put("runId", runId.toString());

            Path processing = move(file, props.dir("processing"));
            Result r = processRecords(fn, runId, lines, report, start);
            Path archive = props.dir("archive").resolve(fn.businessDate().toString());
            if ("FAILED".equals(r.status())) {
                move(processing, file.getParent());   // retried on the next scan; deterministic event IDs make it safe
            } else {
                move(processing, archive);
            }
            return r;
        } finally {
            MDC.remove("correlationId");
        }
    }

    private Result processRecords(FileName fn, UUID runId, List<RecordReader.Line> lines, Map<String, Object> report, long start)
            throws IOException {
        FileSpec spec = fn.spec();
        List<RecordReader.Line> valid = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        Map<String, Integer> errorHistogram = new TreeMap<>();
        List<Map<String, Object>> quarantine = new ArrayList<>();
        Set<String> seenInFile = new HashSet<>();
        int duplicates = 0;

        for (RecordReader.Line line : lines) {
            List<String> errors = line.parseError() != null ? List.of(line.parseError()) : spec.validate(line.values());
            if (!errors.isEmpty()) {
                errors.forEach(e -> errorHistogram.merge(e.replaceAll("'.*'", "'…'"), 1, Integer::sum));
                quarantine.add(Map.of("line", line.number(), "errors", errors, "raw", line.raw()));
                continue;
            }
            String key = naturalKey(fn, line.values());
            if (!seenInFile.add(key)) {
                duplicates++;
                continue;
            }
            valid.add(line);
            keys.add(key);
        }
        Set<String> already = runs.alreadyIngested(fn.tenantId(), spec.name(), keys);
        List<RecordReader.Line> fresh = new ArrayList<>();
        List<String> freshKeys = new ArrayList<>();
        for (int i = 0; i < valid.size(); i++) {
            if (already.contains(keys.get(i))) {
                duplicates++;
            } else {
                fresh.add(valid.get(i));
                freshKeys.add(keys.get(i));
            }
        }

        int total = lines.size();
        double invalidRatio = total == 0 ? 0 : quarantine.size() / (double) total;
        report.put("records", Map.of("total", total, "valid", valid.size(), "quarantined", quarantine.size(),
                "duplicates", duplicates, "new", fresh.size()));
        report.put("invalidRatio", Math.round(invalidRatio * 10000) / 10000.0);
        report.put("maxInvalidRatio", spec.maxInvalidRatio());
        report.put("errorsByType", errorHistogram);
        for (Map<String, Object> q : quarantine) {
            runs.quarantine(runId, (Integer) q.get("line"), (String) q.get("raw"), json.writeValueAsString(q.get("errors")));
        }
        if (!quarantine.isEmpty()) {
            Path qFile = props.dir("quarantine").resolve(fn.fileName() + ".quarantine.jsonl");
            Files.createDirectories(qFile.getParent());
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> q : quarantine) sb.append(json.writeValueAsString(q)).append('\n');
            Files.writeString(qFile, sb.toString());
            report.put("quarantineFile", qFile.getFileName().toString());
        }

        if (invalidRatio > spec.maxInvalidRatio()) {
            return finish(fn, runId, "REJECTED", "TOO_MANY_INVALID_RECORDS", report, total, valid.size(), quarantine.size(),
                    duplicates, 0, start);
        }
        int published = 0;
        try {
            if (spec == FileSpec.SETTLEMENT) {
                List<ReconciliationService.Settlement> settlements = fresh.stream().map(l -> new ReconciliationService.Settlement(
                        str(l.values(), "transaction_id"), new BigDecimal(str(l.values(), "settled_amount")),
                        str(l.values(), "status"))).toList();
                report.put("reconciliation", reconciliation.reconcile(fn.tenantId(), fn.businessDate(), settlements, runId,
                        props.dir("outbound")));
            } else {
                List<EventPublisher.Event> events = new ArrayList<>();
                for (int i = 0; i < fresh.size(); i++) events.add(toEvent(fn, fresh.get(i).values(), freshKeys.get(i)));
                published = publisher.publishAll(events, MDC.get("correlationId"));
            }
        } catch (Exception e) {
            log.error("publishing failed for {}; file will be retried", fn.fileName(), e);
            return finish(fn, runId, "FAILED", "PUBLISH_FAILED: " + e.getClass().getSimpleName(), report, total, valid.size(),
                    quarantine.size(), duplicates, 0, start);
        }
        runs.markIngested(fn.tenantId(), spec.name(), freshKeys, runId);
        String status = quarantine.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_ERRORS";
        return finish(fn, runId, status, null, report, total, valid.size(), quarantine.size(), duplicates, published, start);
    }

    /** Profile updates are keyed per business date so tomorrow's update of the same customer is not a "duplicate". */
    private static String naturalKey(FileName fn, Map<String, Object> values) {
        String key = fn.spec().naturalKey(values);
        return fn.spec() == FileSpec.CUSTOMER_PROFILES ? key + "|" + fn.businessDate() : key;
    }

    private EventPublisher.Event toEvent(FileName fn, Map<String, Object> v, String naturalKey) {
        Map<String, Object> p = new LinkedHashMap<>();
        return switch (fn.spec()) {
            case TXN_HISTORY -> {
                p.put("transactionId", v.get("transaction_id"));
                p.put("customerId", v.get("customer_id"));
                p.put("accountId", v.get("account_id"));
                p.put("eventTime", v.get("event_time"));
                p.put("transactionType", v.get("transaction_type"));
                p.put("channel", v.get("channel"));
                p.put("amount", new BigDecimal(str(v, "amount")));
                p.put("currency", v.get("currency"));
                for (String[] f : new String[][]{{"card_token", "cardToken"}, {"merchant_id", "merchantId"}, {"mcc", "mcc"},
                        {"merchant_country", "merchantCountry"}, {"beneficiary_id", "beneficiaryId"},
                        {"beneficiary_country", "beneficiaryCountry"}, {"device_id", "deviceId"}, {"ip_address", "ipAddress"},
                        {"ip_country", "ipCountry"}}) {
                    p.put(f[1], v.get(f[0]));
                }
                p.put("source", "BATCH");
                yield new EventPublisher.Event(EventType.TransactionReceived, fn.tenantId(), str(v, "customer_id"), naturalKey, p);
            }
            case CHARGEBACKS -> {
                p.put("transactionId", v.get("transaction_id"));
                p.put("customerId", v.get("customer_id"));
                p.put("label", "FRAUD");
                p.put("source", "CHARGEBACK");
                p.put("fraudType", null);
                p.put("chargebackReason", v.get("reason_code"));
                yield new EventPublisher.Event(EventType.FraudConfirmed, fn.tenantId(), str(v, "customer_id"), naturalKey, p);
            }
            case FRAUD_LABELS -> {
                p.put("transactionId", v.get("transactionId"));
                p.put("customerId", v.get("customerId"));
                p.put("label", v.get("label"));
                p.put("source", v.get("source"));
                p.put("fraudType", v.get("fraudType"));
                yield new EventPublisher.Event(EventType.FraudConfirmed, fn.tenantId(), str(v, "customerId"), naturalKey, p);
            }
            case CUSTOMER_PROFILES -> {
                p.put("customerId", v.get("customerId"));
                p.put("segment", v.get("segment"));
                p.put("homeCountry", v.get("homeCountry"));
                p.put("tenureDays", Integer.parseInt(str(v, "tenureDays")));
                p.put("avgAmount90d", new BigDecimal(str(v, "avgAmount90d")));
                p.put("riskTier", v.get("riskTier"));
                p.put("boundDeviceIds", v.getOrDefault("boundDeviceIds", List.of()));
                yield new EventPublisher.Event(EventType.CustomerProfileUpdated, fn.tenantId(), str(v, "customerId"), naturalKey, p);
            }
            case SETTLEMENT -> throw new IllegalStateException("settlement is reconciled, not published");
        };
    }

    private Result finish(FileName fn, UUID runId, String status, String reason, Map<String, Object> report, int total,
                          int valid, int quarantined, int duplicates, int published, long start) throws IOException {
        long ms = (System.nanoTime() - start) / 1_000_000;
        report.put("status", status);
        report.put("reason", reason);
        report.put("eventsPublished", published);
        report.put("processingMs", ms);
        String reportJson = json.writeValueAsString(report);
        runs.finish(runId, status, reason, total, valid, quarantined, duplicates, published, ms, reportJson);
        writeReport(fn.fileName(), reportJson);
        meters.counter("ingestion.files", "type", fn.spec().name(), "status", status).increment();
        meters.counter("ingestion.records", "type", fn.spec().name(), "outcome", "valid").increment(valid);
        meters.counter("ingestion.records", "type", fn.spec().name(), "outcome", "quarantined").increment(quarantined);
        meters.counter("ingestion.records", "type", fn.spec().name(), "outcome", "duplicate").increment(duplicates);
        log.info("file processed file={} status={} reason={} total={} valid={} quarantined={} duplicates={} published={} ms={}",
                fn.fileName(), status, reason, total, valid, quarantined, duplicates, published, ms);
        return new Result(runId, status, reason, report);
    }

    private Result reject(Path file, UUID runId, String sha, String reason, FileName fn, Map<String, Object> report, long start,
                          String detail) throws IOException {
        report.put("status", "REJECTED");
        report.put("reason", reason);
        report.put("detail", detail);
        String reportJson = json.writeValueAsString(report);
        runs.rejectedWithoutClaim(runId, file.getFileName().toString(), sha, reason, fn == null ? null : fn.tenantId(),
                fn == null ? null : fn.spec().name(), fn == null ? null : fn.businessDate(), fn == null ? null : fn.sequence(), reportJson);
        writeReport(file.getFileName().toString(), reportJson);
        move(file, props.dir("rejected"));
        meters.counter("ingestion.files", "type", fn == null ? "UNKNOWN" : fn.spec().name(), "status", "REJECTED").increment();
        log.warn("file rejected file={} reason={} detail={}", file.getFileName(), reason, detail);
        return new Result(runId, "REJECTED", reason, report);
    }

    private void writeReport(String fileName, String reportJson) throws IOException {
        Path dir = props.dir("reports");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName + ".report.json"), reportJson);
    }

    private static Path move(Path file, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(file.getFileName());
        Path moved = Files.move(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Path marker = DoneMarker.pathFor(file);
        if (Files.exists(marker)) {
            Files.move(marker, targetDir.resolve(marker.getFileName()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return moved;
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(Map<String, Object> v, String f) {
        Object o = v.get(f);
        return o == null ? null : o.toString().trim();
    }
}
