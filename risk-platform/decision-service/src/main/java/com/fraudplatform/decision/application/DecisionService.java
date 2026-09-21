package com.fraudplatform.decision.application;

import com.fraudplatform.commons.error.ErrorCode;
import com.fraudplatform.commons.error.PlatformException;
import com.fraudplatform.decision.config.PlatformProperties;
import com.fraudplatform.decision.domain.CustomerProfile;
import com.fraudplatform.decision.domain.DegradedMode;
import com.fraudplatform.decision.domain.Reason;
import com.fraudplatform.decision.domain.ReasonCode;
import com.fraudplatform.decision.domain.ReasonSource;
import com.fraudplatform.decision.domain.RiskDecision;
import com.fraudplatform.decision.domain.RiskLevel;
import com.fraudplatform.decision.domain.Transaction;
import com.fraudplatform.decision.features.EntityState;
import com.fraudplatform.decision.features.FeatureCalculator;
import com.fraudplatform.decision.features.FeatureNames;
import com.fraudplatform.decision.features.FeatureVector;
import com.fraudplatform.decision.features.GraphFeatureStore;
import com.fraudplatform.decision.features.GraphRisk;
import com.fraudplatform.decision.features.ResilientFeatureStore;
import com.fraudplatform.decision.inference.ModelScorer;
import com.fraudplatform.decision.inference.OnnxModelBundle;
import com.fraudplatform.decision.integration.DeviceRiskClient;
import com.fraudplatform.decision.observability.DecisionMetrics;
import com.fraudplatform.decision.persistence.DecisionRepository;
import com.fraudplatform.decision.persistence.IdempotencyRepository;
import com.fraudplatform.decision.strategy.CompiledStrategy;
import com.fraudplatform.decision.strategy.EvaluationContext;
import com.fraudplatform.decision.strategy.StrategyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * The scoring use case.
 *
 * <ol>
 *   <li>Idempotency claim (replays return the stored decision).</li>
 *   <li>Parallel enrichment under per-dependency budgets: profile, feature state, graph, device risk.</li>
 *   <li>Feature computation (fs-1.0) and in-process model inference with a time budget.</li>
 *   <li>Strategy evaluation (rules + model + graph + anomaly, thresholds, fail policy).</li>
 *   <li>One database transaction: transaction row + decision row + idempotency completion + outbox events.</li>
 *   <li>After commit: feature-store update and metrics.</li>
 * </ol>
 * Every dependency failure except PostgreSQL degrades the decision instead of failing the request.
 */
@Service
public class DecisionService {

    private static final Logger log = LoggerFactory.getLogger(DecisionService.class);

    public record Command(Transaction transaction, String clientId, String idempotencyKey, String requestHash,
                          String correlationId, long startNanos) {
    }

    public record Result(RiskDecision decision, boolean replayed) {
    }

    private final ActiveStrategyProvider strategies;
    private final CustomerProfileService profiles;
    private final ResilientFeatureStore featureStore;
    private final GraphFeatureStore graphStore;
    private final Optional<DeviceRiskClient> deviceRisk;
    private final FeatureCalculator calculator = new FeatureCalculator();
    private final ModelScorer scorer;
    private final StrategyEngine engine = new StrategyEngine();
    private final DecisionRepository decisions;
    private final IdempotencyRepository idempotency;
    private final DecisionEventsWriter events;
    private final TransactionTemplate tx;
    private final ExecutorService enrichmentExecutor;
    private final PlatformProperties.Budgets budgets;
    private final PlatformProperties props;
    private final DecisionMetrics metrics;
    private final com.fraudplatform.decision.lab.FaultInjector faults;

    public DecisionService(ActiveStrategyProvider strategies, CustomerProfileService profiles, ResilientFeatureStore featureStore,
                           GraphFeatureStore graphStore, Optional<DeviceRiskClient> deviceRisk, ModelScorer scorer,
                           DecisionRepository decisions, IdempotencyRepository idempotency, DecisionEventsWriter events,
                           TransactionTemplate tx, ExecutorService enrichmentExecutor, PlatformProperties props,
                           DecisionMetrics metrics, com.fraudplatform.decision.lab.FaultInjector faults) {
        this.strategies = strategies;
        this.profiles = profiles;
        this.featureStore = featureStore;
        this.graphStore = graphStore;
        this.deviceRisk = deviceRisk;
        this.scorer = scorer;
        this.decisions = decisions;
        this.idempotency = idempotency;
        this.events = events;
        this.tx = tx;
        this.enrichmentExecutor = enrichmentExecutor;
        this.budgets = props.budgets();
        this.props = props;
        this.metrics = metrics;
        this.faults = faults;
    }

    public Result score(Command cmd) {
        Transaction t = cmd.transaction();
        // 1. idempotency
        if (!idempotency.tryClaim(cmd.clientId(), cmd.idempotencyKey(), t.tenantId(), cmd.requestHash())) {
            return replay(cmd);
        }
        try {
            RiskDecision decision = decide(cmd);
            persist(cmd, decision);
            featureStore.record(t); // after commit: a replay or a failed insert must not count twice
            metrics.recordDecision(decision, (System.nanoTime() - cmd.startNanos()) / 1e6);
            log.info("decision", kv("decision", decision.decision()), kv("riskScore", round(decision.riskScore())),
                    kv("strategyVersion", decision.strategyVersion()), kv("modelVersion", decision.modelVersion()),
                    kv("degraded", decision.degradedModes()), kv("decisionMs", decision.processingMs()),
                    kv("topReason", decision.reasons().isEmpty() ? null : decision.reasons().getFirst().code()));
            return new Result(decision, false);
        } catch (RuntimeException e) {
            idempotency.release(cmd.clientId(), cmd.idempotencyKey());
            throw e;
        }
    }

    private Result replay(Command cmd) {
        var entry = idempotency.find(cmd.clientId(), cmd.idempotencyKey()).orElseThrow(() ->
                new PlatformException(ErrorCode.IDEMPOTENCY_IN_PROGRESS, null));
        if (!entry.requestHash().equals(cmd.requestHash())) {
            throw new PlatformException(ErrorCode.IDEMPOTENCY_KEY_REUSED, null, Map.of("idempotencyKey", cmd.idempotencyKey()));
        }
        if (!"COMPLETED".equals(entry.status())) {
            throw new PlatformException(ErrorCode.IDEMPOTENCY_IN_PROGRESS, null);
        }
        metrics.idempotentReplay(cmd.transaction().tenantId());
        RiskDecision stored = decisions.findById(cmd.transaction().tenantId(), entry.decisionId()).orElseThrow();
        return new Result(stored, true);
    }

    RiskDecision decide(Command cmd) {
        Transaction t = cmd.transaction();
        faults.apply(com.fraudplatform.decision.lab.FaultInjector.Point.CPU_BURN);         // lab only
        faults.apply(com.fraudplatform.decision.lab.FaultInjector.Point.LOCK_CONTENTION);  // lab only
        CompiledStrategy strategy = strategies.forCustomer(t.tenantId(), t.customerId());
        Set<DegradedMode> degraded = EnumSet.noneOf(DegradedMode.class);

        // 2. parallel enrichment, each call bounded by its own budget
        CompletableFuture<CustomerProfileService.Result> profileF =
                async(() -> profiles.find(t.tenantId(), t.customerId()), budgets.profileMs());
        CompletableFuture<ResilientFeatureStore.Loaded> stateF = async(() -> featureStore.load(t), budgets.featureStoreMs());
        CompletableFuture<GraphRisk> graphF = async(() -> graphStore.lookup(t), budgets.graphMs());
        CompletableFuture<Optional<DeviceRiskClient.DeviceRisk>> deviceF = deviceRisk.isPresent() && t.deviceId() != null
                ? async(() -> deviceRisk.get().assess(t), budgets.deviceRiskMs())
                : CompletableFuture.completedFuture(Optional.empty());

        CustomerProfile profile = join(profileF, null, DegradedMode.PROFILE_UNAVAILABLE, degraded)
                .map(r -> {
                    if (r.degraded()) degraded.add(DegradedMode.PROFILE_UNAVAILABLE);
                    return r.profile();
                })
                .orElseGet(() -> CustomerProfile.unknown(t.customerId(), props.tenant(t.tenantId()).defaultHomeCountry()));
        EntityState state = join(stateF, null, DegradedMode.FEATURE_STORE_DEGRADED, degraded)
                .map(l -> {
                    if (l.degraded()) degraded.add(DegradedMode.FEATURE_STORE_DEGRADED);
                    return l.state();
                })
                .orElse(EntityState.empty());
        GraphRisk graph = join(graphF, null, DegradedMode.GRAPH_FEATURES_UNAVAILABLE, degraded).orElse(GraphRisk.none(false));
        if (!graph.available()) degraded.add(DegradedMode.GRAPH_FEATURES_UNAVAILABLE);
        Optional<DeviceRiskClient.DeviceRisk> device = join(deviceF, null, DegradedMode.DEVICE_RISK_UNAVAILABLE, degraded)
                .orElse(Optional.empty());
        if (deviceRisk.isPresent() && t.deviceId() != null && device.isEmpty()) degraded.add(DegradedMode.DEVICE_RISK_UNAVAILABLE);

        // 3. features + model
        FeatureVector features = calculator.compute(t, profile, state, graph);
        Optional<OnnxModelBundle.Scores> scores = scorer.score(t.tenantId(), strategy.modelVersion(), features);
        if (scores.isEmpty()) degraded.add(DegradedMode.MODEL_UNAVAILABLE);
        Double challenger = strategy.challengerModelVersion() == null ? null
                : scorer.challengerProbability(t.tenantId(), strategy.challengerModelVersion(), features).orElse(null);

        // 4. strategy
        EvaluationContext ctx = context(t, profile, features, scores, graph, device);
        StrategyEngine.Signals signals = new StrategyEngine.Signals(
                scores.map(OnnxModelBundle.Scores::probability).orElse(null),
                scores.map(OnnxModelBundle.Scores::anomalyPercentile).orElse(null),
                graph, t.amount().doubleValue(), t.channel(), profile.segment(),
                device.map(DeviceRiskClient.DeviceRisk::riskScore).orElse(null),
                device.map(DeviceRiskClient.DeviceRisk::compromisedIp).orElse(false));
        StrategyEngine.Outcome outcome = engine.evaluate(strategy, ctx, signals);

        List<Reason> reasons = new ArrayList<>(outcome.reasons());
        if (!degraded.isEmpty() && !degraded.equals(Set.of(DegradedMode.MODEL_UNAVAILABLE))) {
            reasons.add(new Reason(ReasonCode.DEGRADED_SIGNALS, ReasonSource.FALLBACK, degraded.toString(), 0.0, null));
        }
        double decisionMs = (System.nanoTime() - cmd.startNanos()) / 1e6;
        return new RiskDecision(UUID.randomUUID(), t.tenantId(), t.transactionId(), outcome.decision(), outcome.riskScore(),
                RiskLevel.of(outcome.riskScore(), outcome.thresholds().review(), outcome.thresholds().decline()),
                signals.modelProbability(), signals.anomalyPercentile(), graph.max(), outcome.rulePoints(), List.copyOf(reasons),
                features.asMap(), scores.isPresent() ? strategy.modelVersion() : null, strategy.version(),
                FeatureNames.SPEC_VERSION, strategy.challengerModelVersion(), challenger, Set.copyOf(degraded),
                Math.round(decisionMs * 1000) / 1000.0, cmd.clientId(), cmd.correlationId(), Instant.now());
    }

    private void persist(Command cmd, RiskDecision d) {
        tx.executeWithoutResult(status -> {
            // lab only: a slow transaction holds its connection (connection-pool exhaustion scenario)
            faults.apply(com.fraudplatform.decision.lab.FaultInjector.Point.DECISION_PERSISTENCE);
            if (!decisions.insertTransaction(cmd.transaction(), "REALTIME")) {
                String existing = decisions.findByTransaction(d.tenantId(), d.transactionId())
                        .map(x -> x.decisionId().toString()).orElse("unknown");
                throw new PlatformException(ErrorCode.DUPLICATE_TRANSACTION, null,
                        Map.of("transactionId", d.transactionId(), "existingDecisionId", existing));
            }
            decisions.insertDecision(d);
            idempotency.complete(cmd.clientId(), cmd.idempotencyKey(), d.decisionId(),
                    "{\"decision\":\"" + d.decision() + "\",\"riskScore\":" + round(d.riskScore()) + "}");
            events.write(cmd.transaction(), d);
        });
    }

    private static EvaluationContext context(Transaction t, CustomerProfile p, FeatureVector f,
                                             Optional<OnnxModelBundle.Scores> scores, GraphRisk graph,
                                             Optional<DeviceRiskClient.DeviceRisk> device) {
        EvaluationContext ctx = new EvaluationContext();
        f.asMap().forEach(ctx::put);
        ctx.put("amount", t.amount().doubleValue())
                .put("channel", t.channel().name())
                .put("transaction_type", t.type().name())
                .put("mcc", t.mcc())
                .put("merchant_country", t.merchantCountry())
                .put("ip_country", t.ipCountry())
                .put("beneficiary_country", t.beneficiaryCountry())
                .put("device_id", t.deviceId())
                .put("card_token", t.cardToken())
                .put("merchant_id", t.merchantId())
                .put("beneficiary_id", t.beneficiaryId())
                .put("customer_segment", p.segment())
                .put("customer_id", t.customerId())
                .put("currency", t.currency())
                .put("graph_risk", graph.max());
        scores.ifPresent(s -> ctx.put("model_probability", s.probability()).put("anomaly_percentile", s.anomalyPercentile()));
        device.ifPresent(d -> ctx.put("device_risk_score", d.riskScore()));
        return ctx;
    }

    private <T> CompletableFuture<T> async(Supplier<T> supplier, long budgetMs) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(() -> {
            if (mdc != null) MDC.setContextMap(mdc);
            try {
                return supplier.get();
            } finally {
                MDC.clear();
            }
        }, enrichmentExecutor).orTimeout(budgetMs, TimeUnit.MILLISECONDS);
    }

    private <T> Optional<T> join(CompletableFuture<T> f, T fallback, DegradedMode mode, Set<DegradedMode> degraded) {
        try {
            return Optional.ofNullable(f.join());
        } catch (RuntimeException e) {
            degraded.add(mode);
            if (e.getCause() instanceof java.util.concurrent.TimeoutException) {
                metrics.dependencyTimeout(mode.name());
            }
            log.warn("dependency degraded mode={} cause={}", mode, e.getCause() == null ? e.toString() : e.getCause().toString());
            return Optional.ofNullable(fallback);
        }
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
