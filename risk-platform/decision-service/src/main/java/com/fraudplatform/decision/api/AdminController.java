package com.fraudplatform.decision.api;

import com.fraudplatform.commons.error.ErrorCode;
import com.fraudplatform.commons.error.PlatformException;
import com.fraudplatform.decision.api.security.ApiClientPrincipal;
import com.fraudplatform.decision.application.ModelGovernanceService;
import com.fraudplatform.decision.application.StrategyAdminService;
import com.fraudplatform.decision.application.StrategySimulationService;
import com.fraudplatform.decision.config.PlatformProperties;
import com.fraudplatform.decision.features.RedisGraphFeatureStore;
import com.fraudplatform.decision.persistence.AuditRepository;
import com.fraudplatform.decision.persistence.ModelRepository;
import com.fraudplatform.decision.persistence.StrategyRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Administrative configuration API (role ADMIN). Admin credentials are tenant-scoped: the tenant in the
 * path must be the caller's tenant. Deployments use optimistic locking via {@code If-Match: <rowVersion>}.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenant}")
public class AdminController {

    private final StrategyAdminService strategies;
    private final ModelGovernanceService models;
    private final AuditRepository audit;
    private final RedisGraphFeatureStore graph;
    private final PlatformProperties props;

    public AdminController(StrategyAdminService strategies, ModelGovernanceService models, AuditRepository audit,
                           RedisGraphFeatureStore graph, PlatformProperties props) {
        this.strategies = strategies;
        this.models = models;
        this.audit = audit;
        this.graph = graph;
        this.props = props;
    }

    private static void authorize(ApiClientPrincipal client, String tenant) {
        if (!client.tenantId().equals(tenant)) {
            throw new PlatformException(ErrorCode.FORBIDDEN, "credential is not valid for tenant " + tenant);
        }
    }

    // ------------------------------------------------------------------ strategies

    @GetMapping("/strategies")
    public List<StrategyAdminService.VersionView> listStrategies(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant) {
        authorize(c, tenant);
        return strategies.list(tenant);
    }

    @GetMapping("/strategies/{version}")
    public StrategyAdminService.VersionView getStrategy(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant,
                                                        @PathVariable String version) {
        authorize(c, tenant);
        return strategies.get(tenant, version);
    }

    @PostMapping("/strategies")
    public ResponseEntity<StrategyAdminService.VersionView> createDraft(@AuthenticationPrincipal ApiClientPrincipal c,
                                                                        @PathVariable String tenant, @RequestBody JsonNode doc) {
        authorize(c, tenant);
        return ResponseEntity.status(HttpStatus.CREATED).body(strategies.createDraft(tenant, doc, c.clientId()));
    }

    @PutMapping("/strategies/{version}")
    public StrategyAdminService.VersionView updateDraft(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant,
                                                        @PathVariable String version, @RequestBody JsonNode doc) {
        authorize(c, tenant);
        return strategies.updateDraft(tenant, version, doc, c.clientId());
    }

    @PostMapping("/strategies/{version}/derive")
    public ResponseEntity<StrategyAdminService.VersionView> derive(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant,
                                                                   @PathVariable String version,
                                                                   @RequestBody StrategyAdminService.DeriveRequest req) {
        authorize(c, tenant);
        return ResponseEntity.status(HttpStatus.CREATED).body(strategies.derive(tenant, version, req, c.clientId()));
    }

    public record ApproveRequest(String comment) {
    }

    @PostMapping("/strategies/{version}/approve")
    public StrategyAdminService.VersionView approve(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant,
                                                    @PathVariable String version, @RequestBody(required = false) ApproveRequest req) {
        authorize(c, tenant);
        return strategies.approve(tenant, version, c.clientId(), req == null ? null : req.comment());
    }

    @PostMapping("/strategies/{version}/retire")
    public StrategyAdminService.VersionView retire(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant,
                                                   @PathVariable String version) {
        authorize(c, tenant);
        return strategies.retire(tenant, version, c.clientId());
    }

    @PostMapping("/strategies/{version}/simulate")
    public StrategySimulationService.Report simulate(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant,
                                                     @PathVariable String version, @RequestParam(defaultValue = "2000") int limit) {
        authorize(c, tenant);
        return strategies.simulate(tenant, version, limit);
    }

    // ------------------------------------------------------------------ deployments

    @GetMapping("/deployments")
    public List<StrategyRepository.Deployment> deployments(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant) {
        authorize(c, tenant);
        return strategies.deployments(tenant);
    }

    public record PromoteRequest(String version, Integer rolloutPercentage, String reason) {
    }

    @PostMapping("/deployments/{env}/promote")
    public ResponseEntity<StrategyRepository.Deployment> promote(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant,
                                                                 @PathVariable String env,
                                                                 @RequestHeader(name = "If-Match", required = false) Integer ifMatch,
                                                                 @RequestBody PromoteRequest req) {
        authorize(c, tenant);
        var d = strategies.promote(tenant, env, req.version(), req.rolloutPercentage() == null ? 100 : req.rolloutPercentage(),
                ifMatch, c.clientId(), req.reason());
        return ResponseEntity.ok().eTag(Integer.toString(d.rowVersion())).body(d);
    }

    public record RollbackRequest(String reason) {
    }

    @PostMapping("/deployments/{env}/rollback")
    public ResponseEntity<StrategyRepository.Deployment> rollback(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant,
                                                                  @PathVariable String env,
                                                                  @RequestHeader(name = "If-Match", required = false) Integer ifMatch,
                                                                  @RequestBody(required = false) RollbackRequest req) {
        authorize(c, tenant);
        var d = strategies.rollback(tenant, env, ifMatch, c.clientId(), req == null ? null : req.reason());
        return ResponseEntity.ok().eTag(Integer.toString(d.rowVersion())).body(d);
    }

    // ------------------------------------------------------------------ models

    @GetMapping("/models")
    public List<ModelRepository.ModelVersion> listModels(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant) {
        authorize(c, tenant);
        return models.list(tenant);
    }

    @PostMapping("/models/{version}/register")
    public ModelRepository.ModelVersion register(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant,
                                                 @PathVariable String version) {
        authorize(c, tenant);
        return models.register(tenant, version, c.clientId());
    }

    public record ModelStatusRequest(String status, String reason) {
    }

    @PostMapping("/models/{version}/status")
    public ModelRepository.ModelVersion modelStatus(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant,
                                                    @PathVariable String version, @RequestBody ModelStatusRequest req) {
        authorize(c, tenant);
        return models.changeStatus(tenant, version, req.status(), c.clientId(), req.reason());
    }

    // ------------------------------------------------------------------ audit & operations

    @GetMapping("/audit")
    public List<AuditRepository.AuditEvent> audit(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant,
                                                  @RequestParam(required = false) String entityType,
                                                  @RequestParam(defaultValue = "100") int limit) {
        authorize(c, tenant);
        return audit.list(tenant, entityType, Math.min(limit, 1000));
    }

    @PostMapping("/graph/reload")
    public Map<String, Object> reloadGraph(@AuthenticationPrincipal ApiClientPrincipal c, @PathVariable String tenant) throws Exception {
        authorize(c, tenant);
        Path file = props.modelsDir().resolve(tenant).resolve("graph").resolve("graph-features-latest.jsonl");
        if (!Files.exists(file)) throw new PlatformException(ErrorCode.NOT_FOUND, "no graph snapshot for " + tenant);
        return Map.of("tenant", tenant, "entitiesLoaded", graph.loadSnapshot(tenant, file));
    }
}
