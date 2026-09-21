package com.fraudplatform.decision.integration;

import com.fraudplatform.commons.integration.IntegrationClient;
import com.fraudplatform.commons.integration.IntegrationException;
import com.fraudplatform.decision.domain.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Device/IP risk vendor ({@code POST /device-intel/v1/assessments}). The assessment is a read-only query,
 * so it is marked idempotent — but within a 60 ms budget there is rarely time for a second attempt.
 * Vendor unavailable ⇒ device treated as unknown (not as high risk): a vendor outage must not decline genuine customers.
 */
public class HttpDeviceRiskClient implements DeviceRiskClient {

    private static final Logger log = LoggerFactory.getLogger(HttpDeviceRiskClient.class);
    static final IntegrationClient.Operation ASSESS = new IntegrationClient.Operation("assess-device", true);

    record AssessmentDto(String assessmentId, Integer riskScore, Boolean compromisedIp, Boolean emulator, Boolean proxy,
                         List<String> signals) {
        boolean valid() {
            return riskScore != null && riskScore >= 0 && riskScore <= 100 && compromisedIp != null;
        }
    }

    private final IntegrationClient client;
    private final Duration deadline;

    public HttpDeviceRiskClient(IntegrationClient client, Duration deadline) {
        this.client = client;
        this.deadline = deadline;
    }

    @Override
    public Optional<DeviceRisk> assess(Transaction t) {
        Map<String, String> body = new java.util.HashMap<>();
        body.put("tenantId", t.tenantId());
        body.put("customerId", t.customerId());
        body.put("deviceId", t.deviceId());
        body.put("ipAddress", t.ipAddress());
        body.put("ipCountry", t.ipCountry());
        try {
            AssessmentDto a = client.post(ASSESS, "/device-intel/v1/assessments", body, null, AssessmentDto.class,
                    AssessmentDto::valid, deadline);
            return Optional.of(new DeviceRisk(a.riskScore() / 100.0, a.compromisedIp(),
                    Boolean.TRUE.equals(a.emulator()), Boolean.TRUE.equals(a.proxy())));
        } catch (IntegrationException e) {
            log.warn("device risk unavailable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void warmUp() {
        client.warmUp("/actuator/health");
    }
}
