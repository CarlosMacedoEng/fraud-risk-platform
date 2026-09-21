package com.fraudplatform.decision.inference;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** The subset of the workbench manifest the service needs to run and audit a model version. */
public record ModelManifest(
        String modelVersion,
        String customerId,
        String featureSpecVersion,
        List<String> features,
        String supervisedFile,
        String supervisedSha256,
        String supervisedInput,
        String probabilityOutput,
        int positiveClassIndex,
        String anomalyFile,
        String anomalySha256,
        String anomalyInput,
        String anomalyScoreOutput,
        List<String> anomalyFeatures,
        double[] percentileGrid,
        double[] rawQuantiles,
        JsonNode raw) {

    public static ModelManifest from(JsonNode m) {
        JsonNode s = m.get("supervised");
        JsonNode a = m.get("anomaly");
        return new ModelManifest(
                m.get("modelVersion").asString(),
                m.get("customerId").asString(),
                m.get("featureSpecVersion").asString(),
                strings(m.get("features")),
                s.get("file").asString(), s.get("sha256").asString(), s.get("inputName").asString(),
                s.get("probabilityOutput").asString(), s.get("positiveClassIndex").asInt(),
                a.get("file").asString(), a.get("sha256").asString(), a.get("inputName").asString(),
                a.get("scoreOutput").asString(), strings(a.get("features")),
                doubles(a.get("percentileGrid")), doubles(a.get("rawQuantiles")), m);
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asString()));
        return List.copyOf(out);
    }

    private static double[] doubles(JsonNode arr) {
        double[] out = new double[arr.size()];
        for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).asDouble();
        return out;
    }
}
