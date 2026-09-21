package com.fraudplatform.decision.observability;

import com.fraudplatform.decision.application.ActiveStrategyProvider;
import com.fraudplatform.decision.config.PlatformProperties;
import com.fraudplatform.decision.inference.ModelRegistry;
import com.fraudplatform.decision.strategy.CompiledStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** TS-16 / J-29: an instance whose active model failed to load must not report ready (unless explicitly allowed). */
class ModelsReadinessTest {

    private final ModelRegistry registry = mock(ModelRegistry.class);
    private final ActiveStrategyProvider provider = mock(ActiveStrategyProvider.class);
    private final PlatformProperties props = new PlatformProperties("dev", null, null, null, null,
            Map.of("aldermoor-bank", new PlatformProperties.Tenant("PT", "EUR")), null, null);

    ModelsReadinessTest() {
        CompiledStrategy strategy = mock(CompiledStrategy.class);
        when(strategy.modelVersion()).thenReturn("aldermoor-bank-lgbm-1.0.0");
        when(provider.get("aldermoor-bank")).thenReturn(strategy);
        when(registry.failures()).thenReturn(Map.of("aldermoor-bank/aldermoor-bank-lgbm-1.0.0", "NoSuchFileException"));
        when(registry.get("aldermoor-bank", "aldermoor-bank-lgbm-1.0.0")).thenReturn(Optional.empty());
    }

    private Health health(boolean requireModels) {
        HealthIndicator indicator = new PlatformHealthIndicators().models(registry, provider, props, requireModels);
        return indicator.health();
    }

    @Test
    void missingActiveModelMakesInstanceUnready() {
        Health h = health(true);
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsEntry("allModelsLoaded", false).containsKey("failures");
    }

    @Test
    void explicitRulesOnlyOptOutKeepsInstanceReady() {
        Health h = health(false);
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("allModelsLoaded", false).containsEntry("requireModels", false);
    }
}
