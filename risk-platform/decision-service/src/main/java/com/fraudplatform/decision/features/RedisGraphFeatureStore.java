package com.fraudplatform.decision.features;

import com.fraudplatform.decision.domain.Transaction;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.DefaultStringRedisConnection;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Looks up precomputed graph risk (ADR-005) from Redis hashes {@code graph:<tenant>:<kind>:<id>}
 * with fields {@code risk}, {@code explain} and {@code asOf}. One pipelined round trip for the four
 * entity kinds. Also loads snapshots exported by the ml-workbench.
 */
public class RedisGraphFeatureStore implements GraphFeatureStore {

    private static final Logger log = LoggerFactory.getLogger(RedisGraphFeatureStore.class);

    private final StringRedisTemplate redis;
    private final CircuitBreaker breaker;
    private final ObjectMapper json;

    public RedisGraphFeatureStore(StringRedisTemplate redis, CircuitBreaker breaker, ObjectMapper json) {
        this.redis = redis;
        this.breaker = breaker;
        this.json = json;
    }

    static String key(String tenant, String kind, String id) {
        return "graph:" + tenant + ":" + kind + ":" + id;
    }

    @Override
    @SuppressWarnings("unchecked")
    public GraphRisk lookup(Transaction tx) {
        String[][] keys = {
                {"device", tx.deviceId()}, {"beneficiary", tx.beneficiaryId()},
                {"merchant", tx.merchantId()}, {"account", tx.customerId()}};
        try {
            List<Object> results = breaker.executeSupplier(() -> redis.executePipelined((RedisCallback<Object>) conn -> {
                StringRedisConnection c = new DefaultStringRedisConnection(conn);
                for (String[] k : keys) {
                    c.hMGet(key(tx.tenantId(), k[0], k[1] == null ? "-" : k[1]), "risk", "explain");
                }
                return null;
            }));
            double[] risk = new double[4];
            Map<String, String> explanations = new HashMap<>();
            for (int i = 0; i < 4; i++) {
                if (keys[i][1] == null) continue;
                List<String> values = (List<String>) results.get(i);
                if (values != null && values.get(0) != null) {
                    risk[i] = Double.parseDouble(values.get(0));
                    if (values.get(1) != null) explanations.put(keys[i][0], values.get(1));
                }
            }
            return new GraphRisk(risk[0], risk[1], risk[2], risk[3], explanations, true);
        } catch (RuntimeException e) {
            log.warn("graph features unavailable: {}", e.toString());
            return GraphRisk.none(false);
        }
    }

    /** Load a workbench snapshot (JSON Lines) into Redis. Returns the number of entities written. */
    public int loadSnapshot(String tenant, Path file) throws IOException {
        int n = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            Map<String, Map<String, String>> batch = new HashMap<>();
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode rec = json.readTree(line);
                String k = key(tenant, rec.get("kind").asString(), rec.get("id").asString());
                batch.put(k, Map.of("risk", rec.get("risk").asString(), "explain", describe(rec),
                        "asOf", rec.get("as_of").asString()));
                if (batch.size() >= 500) {
                    n += flush(batch);
                }
            }
            n += flush(batch);
        }
        return n;
    }

    private int flush(Map<String, Map<String, String>> batch) {
        int size = batch.size();
        redis.executePipelined((RedisCallback<Object>) conn -> {
            StringRedisConnection c = new DefaultStringRedisConnection(conn);
            batch.forEach(c::hMSet);
            return null;
        });
        batch.clear();
        return size;
    }

    /** Investigator-readable one-liner, e.g. "device used by 6 customers in 30d, 2 with confirmed fraud". */
    static String describe(JsonNode rec) {
        JsonNode f = rec.get("features");
        String kind = rec.get("kind").asString();
        return switch (kind) {
            case "device" -> "device used by %d customers in 30d%s".formatted(f.path("distinct_customers").asInt(),
                    f.path("fraud_txns").asDouble() > 0 || f.path("fraud_linked").asDouble() > 0 ? ", linked to confirmed fraud" : "");
            case "beneficiary" -> "beneficiary received funds from %d senders in 30d%s".formatted(
                    f.path("distinct_senders").asInt(), f.path("fraud_inbound").asDouble() > 0 ? ", incl. confirmed fraud" : "");
            case "merchant" -> "merchant: %d cards, %.0f%% round prices, %.0f%% night sales, first seen %d days ago".formatted(
                    f.path("distinct_cards").asInt(), 100 * f.path("round_share").asDouble(),
                    100 * f.path("night_share").asDouble(), f.path("age_days").asInt());
            case "account" -> "account shares devices/IPs with %d other accounts%s".formatted(
                    Math.max(0, f.path("component_accounts").asInt() - 1),
                    f.path("component_fraud_accounts").asDouble() > 0 ? ", incl. accounts with confirmed fraud" : "");
            default -> kind;
        };
    }
}
