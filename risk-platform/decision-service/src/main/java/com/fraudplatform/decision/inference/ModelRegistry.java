package com.fraudplatform.decision.inference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads immutable model versions from the model directory ({@code <modelsDir>/<tenant>/<version>/})
 * and caches them. A version that fails to load (missing file, checksum mismatch, ONNX error) is
 * remembered as failed so the service keeps running in fallback mode and health reports it.
 */
public class ModelRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final Path modelsDir;
    private final ObjectMapper json;
    private final Map<String, OnnxModelBundle> loaded = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();

    public ModelRegistry(Path modelsDir, ObjectMapper json) {
        this.modelsDir = modelsDir;
        this.json = json;
    }

    private static String key(String tenant, String version) {
        return tenant + "/" + version;
    }

    public Optional<OnnxModelBundle> get(String tenant, String version) {
        if (version == null) return Optional.empty();
        String k = key(tenant, version);
        OnnxModelBundle bundle = loaded.get(k);
        if (bundle != null) return Optional.of(bundle);
        if (failures.containsKey(k)) return Optional.empty();
        synchronized (this) {
            bundle = loaded.get(k);
            if (bundle != null) return Optional.of(bundle);
            try {
                Path dir = modelsDir.resolve(tenant).resolve(version);
                ModelManifest manifest = readManifest(dir);
                if (!manifest.customerId().equals(tenant)) {
                    throw new IllegalStateException("manifest tenant " + manifest.customerId() + " != " + tenant);
                }
                bundle = OnnxModelBundle.load(dir, manifest);
                loaded.put(k, bundle);
                log.info("model loaded tenant={} version={} features={}", tenant, version, manifest.features().size());
                return Optional.of(bundle);
            } catch (Exception e) {
                failures.put(k, e.getClass().getSimpleName() + ": " + e.getMessage());
                log.error("model failed to load tenant={} version={}: {}", tenant, version, e.toString());
                return Optional.empty();
            }
        }
    }

    public ModelManifest readManifest(Path versionDir) throws java.io.IOException {
        return ModelManifest.from(json.readTree(Files.readString(versionDir.resolve("manifest.json"))));
    }

    public Path modelsDir() {
        return modelsDir;
    }

    /** Forget a failed load so an operator can retry after fixing the artifact. */
    public void clearFailure(String tenant, String version) {
        failures.remove(key(tenant, version));
    }

    public Map<String, String> failures() {
        return Map.copyOf(failures);
    }

    public java.util.Set<String> loadedVersions() {
        return java.util.Set.copyOf(loaded.keySet());
    }

    @Override
    public void close() {
        loaded.values().forEach(b -> {
            try {
                b.close();
            } catch (Exception e) {
                log.warn("error closing model", e);
            }
        });
    }
}
