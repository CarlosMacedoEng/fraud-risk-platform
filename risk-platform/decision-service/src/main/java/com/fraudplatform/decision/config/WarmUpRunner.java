package com.fraudplatform.decision.config;

import com.fraudplatform.decision.application.ActiveStrategyProvider;
import com.fraudplatform.decision.features.FeatureNames;
import com.fraudplatform.decision.features.FeatureVector;
import com.fraudplatform.decision.inference.ModelRegistry;
import com.fraudplatform.decision.integration.CustomerProfileClient;
import com.fraudplatform.decision.integration.DeviceRiskClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Startup warm-up, executed before the readiness probe reports ACCEPTING_TRAFFIC (runners complete
 * before {@code ApplicationReadyEvent}). Without it, the first requests after every deployment pay for
 * lazy model loading, ONNX session optimisation, JIT compilation and new TCP connections — and blow their
 * dependency budgets, so they are decided in degraded mode (journal J-13).
 */
@Component
@Order(10)
public class WarmUpRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WarmUpRunner.class);

    private final PlatformProperties props;
    private final ActiveStrategyProvider strategies;
    private final ModelRegistry models;
    private final Optional<CustomerProfileClient> profileClient;
    private final Optional<DeviceRiskClient> deviceClient;
    private final JdbcClient jdbc;

    public WarmUpRunner(PlatformProperties props, ActiveStrategyProvider strategies, ModelRegistry models,
                        Optional<CustomerProfileClient> profileClient, Optional<DeviceRiskClient> deviceClient, JdbcClient jdbc) {
        this.props = props;
        this.strategies = strategies;
        this.models = models;
        this.profileClient = profileClient;
        this.deviceClient = deviceClient;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        long start = System.nanoTime();
        Map<String, Double> values = new HashMap<>();
        FeatureNames.BASE.forEach(f -> values.put(f, 0.0));
        FeatureNames.GRAPH.forEach(f -> values.put(f, 0.0));
        values.put("amount_log", 3.0);
        values.put("amount_to_baseline", 1.0);
        FeatureVector dummy = new FeatureVector(values);
        int inferences = 0;
        for (String tenant : props.tenants().keySet()) {
            try {
                var s = strategies.get(tenant);
                for (String version : new String[]{s.modelVersion(), s.challengerModelVersion()}) {
                    var bundle = version == null ? Optional.<com.fraudplatform.decision.inference.OnnxModelBundle>empty()
                            : models.get(tenant, version);
                    if (bundle.isPresent()) {
                        for (int i = 0; i < 50; i++) {
                            bundle.get().score(dummy);
                            inferences++;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("warm-up skipped for tenant {}: {}", tenant, e.toString());
            }
        }
        for (int i = 0; i < 3; i++) {
            profileClient.ifPresent(CustomerProfileClient::warmUp);
            deviceClient.ifPresent(DeviceRiskClient::warmUp);
        }
        for (int i = 0; i < 5; i++) {
            jdbc.sql("SELECT 1").query(Integer.class).single();
        }
        log.info("warm-up completed in {} ms ({} inferences)", (System.nanoTime() - start) / 1_000_000, inferences);
    }
}
