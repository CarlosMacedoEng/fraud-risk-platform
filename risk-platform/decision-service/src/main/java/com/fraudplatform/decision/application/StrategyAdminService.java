package com.fraudplatform.decision.application;

import com.fraudplatform.commons.correlation.Correlation;
import com.fraudplatform.commons.error.ErrorCode;
import com.fraudplatform.commons.error.PlatformException;
import com.fraudplatform.decision.persistence.AuditRepository;
import com.fraudplatform.decision.persistence.ModelRepository;
import com.fraudplatform.decision.persistence.StrategyRepository;
import com.fraudplatform.decision.persistence.StrategyRepository.Deployment;
import com.fraudplatform.decision.persistence.StrategyRepository.StoredStrategy;
import com.fraudplatform.decision.strategy.CompiledStrategy;
import com.fraudplatform.decision.strategy.StrategyCompiler;
import com.fraudplatform.decision.strategy.StrategyValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Safe change management for customer risk strategies.
 *
 * <pre>
 *   DRAFT --approve (four-eyes, full validation, model governance)--> VALIDATED --> RETIRED
 *   promote: dev -> staging -> prod (same immutable artifact, checksum preserved)
 *            rollout 1-99% = canary of a candidate; 100% = activate; rollback = one step back
 * </pre>
 * Every state change is audited with actor, before/after and correlation ID, and emits a
 * {@code ConfigurationChanged} event in the same transaction.
 */
@Service
public class StrategyAdminService {

    private static final Logger log = LoggerFactory.getLogger(StrategyAdminService.class);
    public static final List<String> ENVIRONMENTS = List.of("dev", "staging", "prod");

    public record ValidationResult(List<String> errors, List<String> warnings) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public record VersionView(StoredStrategy version, ValidationResult validation, List<String> activeIn) {
    }

    public record DeriveRequest(String newVersion, String changeSummary, boolean emergency,
                                Map<String, List<String>> listAdditions, Map<String, List<String>> listRemovals,
                                List<String> disableRules, List<String> enableRules) {
    }

    private final StrategyRepository strategies;
    private final ModelRepository models;
    private final AuditRepository audit;
    private final StrategyCompiler compiler;
    private final ActiveStrategyProvider provider;
    private final StrategySimulationService simulation;
    private final AdminEvents events;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final boolean fourEyes;

    public StrategyAdminService(StrategyRepository strategies, ModelRepository models, AuditRepository audit,
                                StrategyCompiler compiler, ActiveStrategyProvider provider,
                                StrategySimulationService simulation, AdminEvents events, TransactionTemplate tx,
                                ObjectMapper json,
                                @org.springframework.beans.factory.annotation.Value("${platform.governance.four-eyes:true}") boolean fourEyes) {
        this.strategies = strategies;
        this.models = models;
        this.audit = audit;
        this.compiler = compiler;
        this.provider = provider;
        this.simulation = simulation;
        this.events = events;
        this.tx = tx;
        this.json = json;
        this.fourEyes = fourEyes;
    }

    // ------------------------------------------------------------------ read

    public List<VersionView> list(String tenant) {
        List<Deployment> deployments = strategies.listDeployments(tenant);
        return strategies.listVersions(tenant).stream()
                .map(v -> new VersionView(v, null, activeIn(v.version(), deployments))).toList();
    }

    public VersionView get(String tenant, String version) {
        StoredStrategy v = find(tenant, version);
        return new VersionView(v, validate(tenant, json.readTree(v.definition())), activeIn(version, strategies.listDeployments(tenant)));
    }

    public List<Deployment> deployments(String tenant) {
        return strategies.listDeployments(tenant);
    }

    private static List<String> activeIn(String version, List<Deployment> deployments) {
        List<String> out = new ArrayList<>();
        for (Deployment d : deployments) {
            if (version.equals(d.activeVersion())) out.add(d.environment());
            if (version.equals(d.candidateVersion())) out.add(d.environment() + " (canary " + d.rolloutPercentage() + "%)");
        }
        return out;
    }

    // ------------------------------------------------------------------ validation

    /** Structural validation (compiler) + governance checks (model registry). */
    public ValidationResult validate(String tenant, JsonNode doc) {
        List<String> errors = new ArrayList<>(compiler.validate(tenant, doc));
        List<String> warnings = new ArrayList<>();
        String primary = doc.path("model").path("version").asString(null);
        if (primary != null) {
            var m = models.find(tenant, primary);
            if (m.isEmpty()) errors.add("model " + primary + " is not registered");
            else if (Set.of("CANDIDATE", "RETIRED").contains(m.get().status())) {
                errors.add("model " + primary + " is " + m.get().status() + "; primary model must be CHAMPION (or SHADOW with approval)");
            } else if (m.get().status().equals("SHADOW")) {
                warnings.add("primary model " + primary + " is SHADOW, not CHAMPION");
            }
        }
        JsonNode ch = doc.path("model").path("challengerVersion");
        if (!ch.isMissingNode() && !ch.isNull()) {
            var m = models.find(tenant, ch.asString());
            if (m.isEmpty()) errors.add("challenger model " + ch.asString() + " is not registered");
            else if (m.get().status().equals("RETIRED")) errors.add("challenger model " + ch.asString() + " is RETIRED");
        }
        return new ValidationResult(errors, warnings);
    }

    // ------------------------------------------------------------------ drafts

    public VersionView createDraft(String tenant, JsonNode doc, String actor) {
        String version = doc.path("version").asString("");
        if (!doc.isObject() || version.isBlank()) {
            throw new PlatformException(ErrorCode.VALIDATION_FAILED, "strategy document with a version is required");
        }
        if (!tenant.equals(doc.path("customerId").asString(""))) {
            throw new PlatformException(ErrorCode.VALIDATION_FAILED, "customerId must equal the tenant in the path");
        }
        tx.executeWithoutResult(s -> {
            try {
                strategies.insertVersion(tenant, version, doc.toString(), StrategyCompiler.checksum(doc), "DRAFT",
                        doc.path("changeSummary").asString(null), actor);
            } catch (DuplicateKeyException e) {
                throw new PlatformException(ErrorCode.VERSION_CONFLICT,
                        "strategy version " + version + " already exists; versions are immutable, use a new version number");
            }
            record(tenant, actor, "STRATEGY_DRAFT_CREATED", version, null, Map.of("status", "DRAFT"));
        });
        return get(tenant, version);
    }

    public VersionView updateDraft(String tenant, String version, JsonNode doc, String actor) {
        if (!version.equals(doc.path("version").asString(""))) {
            throw new PlatformException(ErrorCode.VALIDATION_FAILED, "document version must equal the path version");
        }
        StoredStrategy before = find(tenant, version);
        tx.executeWithoutResult(s -> {
            if (strategies.updateDraftDefinition(tenant, version, doc.toString(), StrategyCompiler.checksum(doc),
                    doc.path("changeSummary").asString(null)) != 1) {
                throw new PlatformException(ErrorCode.INVALID_STATE_TRANSITION, "only DRAFT versions can be edited (status " + before.status() + ")");
            }
            record(tenant, actor, "STRATEGY_DRAFT_UPDATED", version, Map.of("checksum", before.checksum()),
                    Map.of("checksum", StrategyCompiler.checksum(doc)));
        });
        return get(tenant, version);
    }

    /**
     * Create a new DRAFT from an existing version with a restricted set of edits (lists and rule
     * switches) — the fast path for emergency blocks such as "block this device now".
     */
    public VersionView derive(String tenant, String fromVersion, DeriveRequest req, String actor) {
        ObjectNode doc = (ObjectNode) json.readTree(find(tenant, fromVersion).definition());
        doc.put("version", req.newVersion());
        doc.put("changeSummary", req.changeSummary());
        ObjectNode lists = (ObjectNode) doc.path("lists");
        if (req.listAdditions() != null) {
            req.listAdditions().forEach((name, values) -> {
                Set<String> merged = new LinkedHashSet<>();
                lists.path(name).forEach(v -> merged.add(v.asString()));
                merged.addAll(values);
                ArrayNode arr = lists.putArray(name);
                merged.forEach(arr::add);
            });
        }
        if (req.listRemovals() != null) {
            req.listRemovals().forEach((name, values) -> {
                ArrayNode arr = json.createArrayNode();
                lists.path(name).forEach(v -> {
                    if (!values.contains(v.asString())) arr.add(v.asString());
                });
                lists.set(name, arr);
            });
        }
        for (String field : new String[]{"rules", "emergencyRules"}) {
            for (JsonNode rule : doc.path(field)) {
                String id = rule.path("id").asString();
                if (req.disableRules() != null && req.disableRules().contains(id)) ((ObjectNode) rule).put("enabled", false);
                if (req.enableRules() != null && req.enableRules().contains(id)) ((ObjectNode) rule).put("enabled", true);
            }
        }
        tx.executeWithoutResult(s -> {
            try {
                strategies.insertVersion(tenant, req.newVersion(), doc.toString(), StrategyCompiler.checksum(doc), "DRAFT",
                        req.changeSummary(), actor, req.emergency(), fromVersion);
            } catch (DuplicateKeyException e) {
                throw new PlatformException(ErrorCode.VERSION_CONFLICT, "strategy version " + req.newVersion() + " already exists");
            }
            record(tenant, actor, req.emergency() ? "STRATEGY_EMERGENCY_DERIVED" : "STRATEGY_DERIVED", req.newVersion(),
                    Map.of("from", fromVersion), Map.of("status", "DRAFT", "emergency", req.emergency()));
        });
        return get(tenant, req.newVersion());
    }

    // ------------------------------------------------------------------ approval

    public VersionView approve(String tenant, String version, String actor, String comment) {
        StoredStrategy v = find(tenant, version);
        if (!v.status().equals("DRAFT")) {
            throw new PlatformException(ErrorCode.INVALID_STATE_TRANSITION, "only DRAFT versions can be approved (status " + v.status() + ")");
        }
        if (fourEyes && v.createdBy().equals(actor)) {
            throw new PlatformException(ErrorCode.FORBIDDEN, "four-eyes principle: the author of a change cannot approve it",
                    Map.of("createdBy", v.createdBy()));
        }
        JsonNode doc = json.readTree(v.definition());
        ValidationResult result = validate(tenant, doc);
        if (!result.valid()) throw new StrategyValidationException(result.errors());
        tx.executeWithoutResult(s -> {
            if (strategies.updateStatus(tenant, version, "DRAFT", "VALIDATED", actor) != 1) {
                throw new PlatformException(ErrorCode.VERSION_CONFLICT, "strategy changed concurrently");
            }
            record(tenant, actor, "STRATEGY_APPROVED", version, Map.of("status", "DRAFT"),
                    Map.of("status", "VALIDATED", "comment", comment == null ? "" : comment, "warnings", result.warnings()));
        });
        return get(tenant, version);
    }

    public VersionView retire(String tenant, String version, String actor) {
        List<String> active = activeIn(version, strategies.listDeployments(tenant));
        if (!active.isEmpty()) {
            throw new PlatformException(ErrorCode.INVALID_STATE_TRANSITION, "version is deployed in " + active);
        }
        tx.executeWithoutResult(s -> {
            if (strategies.updateStatus(tenant, version, "VALIDATED", "RETIRED", actor) != 1) {
                throw new PlatformException(ErrorCode.INVALID_STATE_TRANSITION, "only VALIDATED versions can be retired");
            }
            record(tenant, actor, "STRATEGY_RETIRED", version, Map.of("status", "VALIDATED"), Map.of("status", "RETIRED"));
        });
        return get(tenant, version);
    }

    // ------------------------------------------------------------------ deployment

    public Deployment promote(String tenant, String env, String version, int rolloutPercentage, Integer ifMatch,
                              String actor, String reason) {
        requireEnv(env);
        if (rolloutPercentage < 1 || rolloutPercentage > 100) {
            throw new PlatformException(ErrorCode.VALIDATION_FAILED, "rolloutPercentage must be between 1 and 100");
        }
        StoredStrategy v = find(tenant, version);
        if (!v.status().equals("VALIDATED")) {
            throw new PlatformException(ErrorCode.INVALID_STATE_TRANSITION, "only VALIDATED versions can be deployed (status " + v.status() + ")");
        }
        int idx = ENVIRONMENTS.indexOf(env);
        if (idx > 0 && !v.emergency()) {
            String lower = ENVIRONMENTS.get(idx - 1);
            Deployment below = strategies.findDeployment(tenant, lower).orElse(null);
            if (below == null || !version.equals(below.activeVersion())) {
                throw new PlatformException(ErrorCode.INVALID_STATE_TRANSITION,
                        "promotion order: version " + version + " must be fully active in " + lower + " before " + env,
                        Map.of("activeIn" + capitalize(lower), below == null ? "none" : below.activeVersion()));
            }
        }
        if (v.emergency() && (reason == null || reason.isBlank())) {
            throw new PlatformException(ErrorCode.VALIDATION_FAILED, "emergency promotion requires a reason");
        }
        Deployment current = strategies.findDeployment(tenant, env).orElse(null);
        tx.executeWithoutResult(s -> {
            boolean ok;
            String action;
            if (current == null) {
                if (rolloutPercentage != 100) {
                    throw new PlatformException(ErrorCode.INVALID_STATE_TRANSITION, "first deployment of an environment must be 100%");
                }
                ok = strategies.createDeployment(tenant, env, version, actor);
                action = "STRATEGY_ACTIVATED";
            } else {
                checkIfMatch(current, ifMatch);
                if (rolloutPercentage == 100) {
                    if (version.equals(current.activeVersion()) && current.candidateVersion() == null) {
                        throw new PlatformException(ErrorCode.INVALID_STATE_TRANSITION, "version already active in " + env);
                    }
                    ok = strategies.activate(tenant, env, version, actor, current.rowVersion());
                    action = version.equals(current.candidateVersion()) ? "STRATEGY_ROLLOUT_COMPLETED" : "STRATEGY_ACTIVATED";
                } else {
                    if (version.equals(current.activeVersion())) {
                        throw new PlatformException(ErrorCode.INVALID_STATE_TRANSITION, "canary version must differ from the active version");
                    }
                    ok = strategies.setCanary(tenant, env, version, rolloutPercentage, actor, current.rowVersion());
                    action = "STRATEGY_CANARY";
                }
            }
            if (!ok) throw conflict(tenant, env);
            record(tenant, actor, v.emergency() ? action + "_EMERGENCY" : action, env,
                    current == null ? null : deploymentState(current),
                    Map.of("version", version, "rolloutPercentage", rolloutPercentage, "reason", reason == null ? "" : reason));
            events.configurationChanged(tenant, env, action, version, current == null ? null : current.activeVersion(),
                    rolloutPercentage, actor, reason);
        });
        refreshIfLocal(tenant, env);
        log.info("strategy deployment changed tenant={} env={} version={} rollout={}% actor={}", tenant, env, version,
                rolloutPercentage, actor);
        return strategies.findDeployment(tenant, env).orElseThrow();
    }

    public Deployment rollback(String tenant, String env, Integer ifMatch, String actor, String reason) {
        requireEnv(env);
        Deployment current = strategies.findDeployment(tenant, env)
                .orElseThrow(() -> new PlatformException(ErrorCode.NOT_FOUND, "nothing deployed in " + env));
        checkIfMatch(current, ifMatch);
        if (current.candidateVersion() == null && current.previousVersion() == null) {
            throw new PlatformException(ErrorCode.INVALID_STATE_TRANSITION, "no previous version to roll back to");
        }
        tx.executeWithoutResult(s -> {
            if (!strategies.rollback(tenant, env, actor, current.rowVersion())) throw conflict(tenant, env);
            Deployment after = strategies.findDeployment(tenant, env).orElseThrow();
            record(tenant, actor, "STRATEGY_ROLLBACK", env, deploymentState(current), deploymentState(after));
            events.configurationChanged(tenant, env, "STRATEGY_ROLLBACK", after.activeVersion(), current.activeVersion(),
                    100, actor, reason);
        });
        refreshIfLocal(tenant, env);
        return strategies.findDeployment(tenant, env).orElseThrow();
    }

    public StrategySimulationService.Report simulate(String tenant, String version, int limit) {
        CompiledStrategy candidate;
        try {
            candidate = compiler.compile(tenant, json.readTree(find(tenant, version).definition()));
        } catch (StrategyValidationException e) {
            throw e;
        }
        return simulation.simulate(tenant, provider.get(tenant), candidate, Math.min(Math.max(limit, 1), 20000));
    }

    // ------------------------------------------------------------------ helpers

    private StoredStrategy find(String tenant, String version) {
        return strategies.findVersion(tenant, version)
                .orElseThrow(() -> new PlatformException(ErrorCode.NOT_FOUND, "strategy version " + version + " not found"));
    }

    private void refreshIfLocal(String tenant, String env) {
        if (env.equals(provider.environment())) provider.refresh(tenant);
    }

    private static void requireEnv(String env) {
        if (!ENVIRONMENTS.contains(env)) {
            throw new PlatformException(ErrorCode.VALIDATION_FAILED, "environment must be one of " + ENVIRONMENTS);
        }
    }

    private static void checkIfMatch(Deployment current, Integer ifMatch) {
        if (ifMatch != null && ifMatch != current.rowVersion()) {
            throw new PlatformException(ErrorCode.VERSION_CONFLICT, "deployment was modified (If-Match " + ifMatch
                    + ", current " + current.rowVersion() + ")", Map.of("currentRowVersion", current.rowVersion()));
        }
    }

    private PlatformException conflict(String tenant, String env) {
        int now = strategies.findDeployment(tenant, env).map(Deployment::rowVersion).orElse(-1);
        return new PlatformException(ErrorCode.VERSION_CONFLICT, "deployment modified concurrently",
                Map.of("currentRowVersion", now));
    }

    private static Map<String, Object> deploymentState(Deployment d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", d.activeVersion());
        m.put("previous", d.previousVersion());
        m.put("candidate", d.candidateVersion());
        m.put("rolloutPercentage", d.rolloutPercentage());
        m.put("rowVersion", d.rowVersion());
        return m;
    }

    private void record(String tenant, String actor, String action, String entityId, Map<String, ?> before, Map<String, ?> after) {
        String entityType = entityId != null && ENVIRONMENTS.contains(entityId) ? "deployment" : "strategy";
        audit.record(tenant, actor, action, entityType, entityId, before == null ? null : json.writeValueAsString(before),
                after == null ? null : json.writeValueAsString(after), MDC.get(Correlation.MDC_CORRELATION_ID));
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
