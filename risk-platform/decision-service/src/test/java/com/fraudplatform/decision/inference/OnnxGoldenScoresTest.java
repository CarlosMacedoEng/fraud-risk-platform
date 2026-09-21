package com.fraudplatform.decision.inference;

import com.fraudplatform.decision.TestPaths;
import com.fraudplatform.decision.features.FeatureNames;
import com.fraudplatform.decision.features.FeatureVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The Java runtime must reproduce the Python/ONNX outputs recorded at export time. */
class OnnxGoldenScoresTest {

    static final ObjectMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @CsvSource({"aldermoor-bank,aldermoor-bank-lgbm-1.0.0", "aldermoor-bank,aldermoor-bank-lgbm-1.1.0",
            "quillon-pay,quillon-pay-lgbm-1.0.0", "quillon-pay,quillon-pay-lgbm-1.1.0"})
    void javaInferenceMatchesGoldenScores(String tenant, String version) throws Exception {
        ModelRegistry registry = new ModelRegistry(TestPaths.MODELS, JSON);
        OnnxModelBundle bundle = registry.get(tenant, version).orElseThrow(() ->
                new AssertionError("model failed to load: " + registry.failures()));
        Path golden = TestPaths.MODELS.resolve(tenant).resolve(version).resolve("golden_scores.jsonl");
        int n = 0;
        for (String line : Files.readAllLines(golden)) {
            JsonNode row = JSON.readTree(line);
            Map<String, Double> values = new HashMap<>();
            row.get("features").properties().forEach(e -> values.put(e.getKey(), e.getValue().asDouble()));
            FeatureNames.GRAPH.forEach(g -> values.putIfAbsent(g, 0.0));
            OnnxModelBundle.Scores s = bundle.score(new FeatureVector(values));
            assertThat(s.probability()).as(row.get("transactionId").asString())
                    .isCloseTo(row.get("expectedProbability").asDouble(), within(1e-5));
            assertThat(s.anomalyRaw()).isCloseTo(row.get("expectedAnomalyRaw").asDouble(), within(1e-5));
            assertThat(s.anomalyPercentile()).isCloseTo(row.get("expectedAnomalyPercentile").asDouble(), within(1e-4));
            n++;
        }
        assertThat(n).isEqualTo(300);
        registry.close();
    }

    @Test
    void tamperedArtifactIsRejected() throws Exception {
        Path tmp = Files.createTempDirectory("models");
        Path src = TestPaths.MODELS.resolve("aldermoor-bank").resolve("aldermoor-bank-lgbm-1.0.0");
        Path dst = Files.createDirectories(tmp.resolve("aldermoor-bank").resolve("aldermoor-bank-lgbm-1.0.0"));
        for (String f : new String[]{"manifest.json", "supervised.onnx", "anomaly.onnx"}) {
            Files.copy(src.resolve(f), dst.resolve(f));
        }
        byte[] bytes = Files.readAllBytes(dst.resolve("supervised.onnx"));
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(dst.resolve("supervised.onnx"), bytes);

        ModelRegistry registry = new ModelRegistry(tmp, JSON);
        assertThat(registry.get("aldermoor-bank", "aldermoor-bank-lgbm-1.0.0")).isEmpty();
        assertThat(registry.failures().values()).anyMatch(msg -> msg.contains("checksum mismatch"));
    }
}
