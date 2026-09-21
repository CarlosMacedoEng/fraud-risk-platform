package com.fraudplatform.decision.config;

import com.fraudplatform.commons.integration.IntegrationClient;
import com.fraudplatform.decision.integration.CaseManagementClient;
import com.fraudplatform.decision.integration.CustomerProfileClient;
import com.fraudplatform.decision.integration.DeviceRiskClient;
import com.fraudplatform.decision.integration.HttpCustomerProfileClient;
import com.fraudplatform.decision.integration.HttpDeviceRiskClient;
import com.fraudplatform.decision.integration.ModelServiceClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

/** Outbound clients, each enabled independently ({@code platform.integrations.endpoints.<name>.enabled}). */
@Configuration
public class IntegrationConfig {

    static IntegrationClient client(String name, IntegrationsProperties props, ObjectMapper json, MeterRegistry meters) {
        IntegrationsProperties.Endpoint e = props.get(name);
        return new IntegrationClient(new IntegrationClient.Settings(name, e.baseUrl(),
                Duration.ofMillis(e.connectTimeoutMs()), Duration.ofMillis(e.attemptTimeoutMs()), Math.max(1, e.maxAttempts()),
                Duration.ofMillis(Math.max(1, e.initialBackoffMs())), Math.max(1, e.maxConcurrentCalls()), 50f,
                Duration.ofSeconds(10), Map.of("User-Agent", "fraud-decision-service/1.0")), json, meters);
    }

    @Bean
    @ConditionalOnProperty(name = "platform.integrations.endpoints.customer-profile.enabled", havingValue = "true")
    CustomerProfileClient customerProfileClient(IntegrationsProperties props, ObjectMapper json, MeterRegistry meters) {
        return new HttpCustomerProfileClient(client("customer-profile", props, json, meters),
                Duration.ofMillis(props.get("customer-profile").deadlineMs()));
    }

    @Bean
    @ConditionalOnProperty(name = "platform.integrations.endpoints.device-risk.enabled", havingValue = "true")
    DeviceRiskClient deviceRiskClient(IntegrationsProperties props, ObjectMapper json, MeterRegistry meters) {
        return new HttpDeviceRiskClient(client("device-risk", props, json, meters),
                Duration.ofMillis(props.get("device-risk").deadlineMs()));
    }

    @Bean
    @ConditionalOnProperty(name = "platform.integrations.endpoints.case-management.enabled", havingValue = "true")
    CaseManagementClient caseManagementClient(IntegrationsProperties props, ObjectMapper json, MeterRegistry meters) {
        return new CaseManagementClient(client("case-management", props, json, meters),
                Duration.ofMillis(props.get("case-management").deadlineMs()));
    }

    @Bean
    @ConditionalOnProperty(name = "platform.integrations.endpoints.model-service.enabled", havingValue = "true")
    ModelServiceClient modelServiceClient(IntegrationsProperties props, ObjectMapper json, MeterRegistry meters) {
        return new ModelServiceClient(client("model-service", props, json, meters),
                Duration.ofMillis(props.get("model-service").deadlineMs()));
    }
}
