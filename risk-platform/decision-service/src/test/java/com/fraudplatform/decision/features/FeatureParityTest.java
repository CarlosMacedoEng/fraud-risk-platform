package com.fraudplatform.decision.features;

import com.fraudplatform.decision.TestPaths;
import com.fraudplatform.decision.domain.Channel;
import com.fraudplatform.decision.domain.CustomerProfile;
import com.fraudplatform.decision.domain.Transaction;
import com.fraudplatform.decision.domain.TransactionType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Training/serving parity: replays the event stream exported by the Python workbench through the Java
 * FeatureCalculator (with the in-memory store) and requires identical features. A failure here means
 * the model would receive different inputs in production than in training.
 */
class FeatureParityTest {

    static final ObjectMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"aldermoor-bank", "quillon-pay"})
    void javaFeaturesMatchPythonFeatures(String tenant) throws Exception {
        List<String> mismatches = replay(tenant, new InMemoryFeatureStore());
        assertThat(mismatches).isEmpty();
    }

    /** Shared with the Redis parity test. Returns human-readable mismatches. */
    static List<String> replay(String tenant, FeatureStore store) throws Exception {
        Path file = TestPaths.MODELS.resolve(tenant).resolve("parity").resolve("feature_parity_fs-1.0.jsonl");
        FeatureCalculator calc = new FeatureCalculator();
        List<String> mismatches = new ArrayList<>();
        int rows = 0;
        for (String line : Files.readAllLines(file)) {
            JsonNode row = JSON.readTree(line);
            Transaction tx = transaction(tenant, row.get("event"));
            CustomerProfile profile = profile(row.get("profile"));
            FeatureVector v = calc.compute(tx, profile, store.load(tx), GraphRisk.none(true));
            store.record(tx);
            JsonNode expected = row.get("expected");
            for (String name : FeatureNames.BASE) {
                double exp = expected.get(name).asDouble();
                double act = (float) v.get(name); // Python stores float32
                if (Math.abs(exp - act) > 1e-5 * Math.max(1.0, Math.abs(exp))) {
                    mismatches.add("%s %s: python=%s java=%s".formatted(tx.transactionId(), name, exp, act));
                }
            }
            rows++;
        }
        assertThat(rows).isGreaterThan(200);
        return mismatches;
    }

    static Transaction transaction(String tenant, JsonNode e) {
        return new Transaction(tenant, e.get("transactionId").asString(), e.get("customerId").asString(),
                e.get("accountId").asString(), Instant.parse(e.get("eventTime").asString()),
                TransactionType.valueOf(e.get("transactionType").asString()), Channel.valueOf(e.get("channel").asString()),
                new BigDecimal(e.get("amount").asString()), e.get("currency").asString(), text(e, "cardToken"),
                text(e, "merchantId"), text(e, "mcc"), text(e, "merchantCountry"), text(e, "beneficiaryId"),
                text(e, "beneficiaryCountry"), text(e, "deviceId"), text(e, "ipAddress"), text(e, "ipCountry"));
    }

    static CustomerProfile profile(JsonNode p) {
        Set<String> bound = new HashSet<>();
        p.get("boundDeviceIds").forEach(d -> bound.add(d.asString()));
        return new CustomerProfile(p.get("customerId").asString(), p.get("segment").asString(), p.get("homeCountry").asString(),
                p.get("tenureDays").asInt(), p.get("avgAmount90d").asDouble(), p.get("riskTier").asString(), bound, false);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
