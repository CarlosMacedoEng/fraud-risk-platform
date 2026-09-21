package com.fraudplatform.decision.inference;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.fraudplatform.decision.features.FeatureVector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * One model version: supervised classifier + anomaly detector, both ONNX, loaded in-process (ADR-001).
 * {@link OrtSession#run} is thread-safe, so a single bundle is shared by all request threads.
 */
public final class OnnxModelBundle implements AutoCloseable {

    public record Scores(double probability, double anomalyRaw, double anomalyPercentile) {
    }

    private final ModelManifest manifest;
    private final OrtEnvironment env;
    private final OrtSession supervised;
    private final OrtSession anomaly;

    private OnnxModelBundle(ModelManifest manifest, OrtEnvironment env, OrtSession supervised, OrtSession anomaly) {
        this.manifest = manifest;
        this.env = env;
        this.supervised = supervised;
        this.anomaly = anomaly;
    }

    /** Load a version directory, refusing artifacts whose SHA-256 does not match the manifest. */
    public static OnnxModelBundle load(Path dir, ModelManifest manifest) throws IOException, OrtException {
        Path sup = dir.resolve(manifest.supervisedFile());
        Path ano = dir.resolve(manifest.anomalyFile());
        verify(sup, manifest.supervisedSha256());
        verify(ano, manifest.anomalySha256());
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        // One intra-op thread per call: request-level parallelism comes from the web server threads,
        // so letting each inference fan out to all cores would only add contention under load.
        opts.setIntraOpNumThreads(1);
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        return new OnnxModelBundle(manifest, env, env.createSession(sup.toString(), opts), env.createSession(ano.toString(), opts));
    }

    static void verify(Path file, String expectedSha256) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            String actual = HexFormat.of().formatHex(digest);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new IOException("checksum mismatch for " + file + ": expected " + expectedSha256 + " got " + actual);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Scores score(FeatureVector features) throws OrtException {
        double probability = probability(features);
        double raw = anomalyRaw(features);
        return new Scores(probability, raw, AnomalyCalibration.percentile(raw, manifest.rawQuantiles(), manifest.percentileGrid()));
    }

    public double probability(FeatureVector features) throws OrtException {
        float[][] input = {features.toArray(manifest.features())};
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, input);
             OrtSession.Result result = supervised.run(Map.of(manifest.supervisedInput(), tensor))) {
            OnnxValue value = result.get(manifest.probabilityOutput()).orElseThrow();
            float[][] probs = (float[][]) value.getValue();
            return probs[0][manifest.positiveClassIndex()];
        }
    }

    public double anomalyRaw(FeatureVector features) throws OrtException {
        float[][] input = {features.toArray(manifest.anomalyFeatures())};
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, input);
             OrtSession.Result result = anomaly.run(Map.of(manifest.anomalyInput(), tensor))) {
            Object value = result.get(manifest.anomalyScoreOutput()).orElseThrow().getValue();
            float score = value instanceof float[][] m ? m[0][0] : ((float[]) value)[0];
            return -score; // manifest: raw anomaly = negated decision_function (higher = more anomalous)
        }
    }

    public ModelManifest manifest() {
        return manifest;
    }

    @Override
    public void close() throws OrtException {
        supervised.close();
        anomaly.close();
    }
}
