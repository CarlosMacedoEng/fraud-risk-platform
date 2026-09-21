package com.fraudplatform.decision.application;

import com.fraudplatform.commons.correlation.Correlation;
import com.fraudplatform.commons.error.ErrorCode;
import com.fraudplatform.commons.error.PlatformException;
import com.fraudplatform.decision.inference.ModelManifest;
import com.fraudplatform.decision.inference.ModelRegistry;
import com.fraudplatform.decision.persistence.AuditRepository;
import com.fraudplatform.decision.persistence.ModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Model lifecycle: CANDIDATE → SHADOW → CHAMPION → RETIRED (and SHADOW → CANDIDATE on rejection).
 * Registration verifies the artifact checksums by actually loading the model. Promotion to CHAMPION
 * demotes the previous champion to SHADOW in the same transaction (the database enforces a single
 * champion per tenant). Every change is audited and emits {@code ModelVersionPromoted}.
 */
@Service
public class ModelGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(ModelGovernanceService.class);

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "CANDIDATE", Set.of("SHADOW", "RETIRED"),
            "SHADOW", Set.of("CHAMPION", "CANDIDATE", "RETIRED"),
            "CHAMPION", Set.of("SHADOW"),
            "RETIRED", Set.of());

    private final ModelRepository models;
    private final ModelRegistry registry;
    private final AuditRepository audit;
    private final AdminEvents events;
    private final TransactionTemplate tx;

    public ModelGovernanceService(ModelRepository models, ModelRegistry registry, AuditRepository audit,
                                  AdminEvents events, TransactionTemplate tx) {
        this.models = models;
        this.registry = registry;
        this.audit = audit;
        this.events = events;
        this.tx = tx;
    }

    public List<ModelRepository.ModelVersion> list(String tenant) {
        return models.list(tenant);
    }

    public ModelRepository.ModelVersion register(String tenant, String version, String actor) {
        Path dir = registry.modelsDir().resolve(tenant).resolve(version);
        if (!Files.exists(dir.resolve("manifest.json"))) {
            throw new PlatformException(ErrorCode.NOT_FOUND, "no model artifacts at " + tenant + "/" + version);
        }
        registry.clearFailure(tenant, version);
        if (registry.get(tenant, version).isEmpty()) {
            throw new PlatformException(ErrorCode.INVALID_STATE_TRANSITION, "model failed to load",
                    Map.of("failures", registry.failures()));
        }
        try {
            ModelManifest m = registry.readManifest(dir);
            if (models.register(tenant, version, m.featureSpecVersion(), m.supervisedSha256(), m.anomalySha256(),
                    m.raw().toString(), actor)) {
                audit.record(tenant, actor, "MODEL_REGISTERED", "model", version, null,
                        "{\"status\":\"CANDIDATE\"}", MDC.get(Correlation.MDC_CORRELATION_ID));
                log.info("model registered tenant={} version={}", tenant, version);
            }
        } catch (java.io.IOException e) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "cannot read manifest", Map.of(), e);
        }
        return models.find(tenant, version).orElseThrow();
    }

    public ModelRepository.ModelVersion changeStatus(String tenant, String version, String target, String actor, String reason) {
        var current = models.find(tenant, version)
                .orElseThrow(() -> new PlatformException(ErrorCode.NOT_FOUND, "model not registered: " + version));
        if (!ALLOWED.getOrDefault(current.status(), Set.of()).contains(target)) {
            throw new PlatformException(ErrorCode.INVALID_STATE_TRANSITION,
                    "cannot move model from " + current.status() + " to " + target,
                    Map.of("allowed", ALLOWED.getOrDefault(current.status(), Set.of())));
        }
        tx.executeWithoutResult(s -> {
            if (target.equals("CHAMPION")) {
                models.champion(tenant).ifPresent(prev -> {
                    models.setStatus(tenant, prev, "CHAMPION", "SHADOW", actor);
                    audit.record(tenant, actor, "MODEL_DEMOTED", "model", prev, "{\"status\":\"CHAMPION\"}",
                            "{\"status\":\"SHADOW\"}", MDC.get(Correlation.MDC_CORRELATION_ID));
                });
            }
            if (models.setStatus(tenant, version, current.status(), target, actor) != 1) {
                throw new PlatformException(ErrorCode.VERSION_CONFLICT, "model status changed concurrently");
            }
            audit.record(tenant, actor, "MODEL_STATUS_CHANGED", "model", version,
                    "{\"status\":\"" + current.status() + "\"}", "{\"status\":\"" + target + "\",\"reason\":" + jsonString(reason) + "}",
                    MDC.get(Correlation.MDC_CORRELATION_ID));
            events.modelStatusChanged(tenant, version, current.status(), target, actor, reason);
        });
        return models.find(tenant, version).orElseThrow();
    }

    /** Register every model directory on disk; strategies' primary models become champion, challengers shadow. */
    public void bootstrap(String tenant, String primary, String challenger) {
        for (String v : new String[]{primary, challenger}) {
            if (v == null || models.find(tenant, v).isPresent()) continue;
            try {
                register(tenant, v, "bootstrap");
                if (v.equals(primary)) {
                    changeStatus(tenant, v, "SHADOW", "bootstrap", "initial registration");
                    changeStatus(tenant, v, "CHAMPION", "bootstrap", "initial champion");
                } else {
                    changeStatus(tenant, v, "SHADOW", "bootstrap", "challenger in shadow mode");
                }
            } catch (PlatformException e) {
                log.warn("model bootstrap skipped tenant={} version={}: {}", tenant, v, e.getMessage());
            }
        }
    }

    static String jsonString(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
