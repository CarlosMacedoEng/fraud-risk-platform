package com.fraudplatform.decision.integration;

import com.fraudplatform.commons.integration.IntegrationClient;
import com.fraudplatform.commons.integration.IntegrationException;
import com.fraudplatform.decision.domain.CustomerProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Customer profile lookup against the customer's CRM API ({@code GET /crm/v1/tenants/{t}/customers/{id}}).
 * Hot path: one attempt within the profile budget; any failure degrades to the conservative default profile.
 */
public class HttpCustomerProfileClient implements CustomerProfileClient {

    private static final Logger log = LoggerFactory.getLogger(HttpCustomerProfileClient.class);
    static final IntegrationClient.Operation GET_PROFILE = new IntegrationClient.Operation("get-profile", true);

    record ProfileDto(String customerId, String segment, String homeCountry, Integer tenureDays, Double avgAmount90d,
                      String riskTier, List<String> boundDeviceIds) {
        boolean valid() {
            return customerId != null && segment != null && homeCountry != null && homeCountry.length() == 2
                    && tenureDays != null && avgAmount90d != null && riskTier != null;
        }
    }

    private final IntegrationClient client;
    private final Duration deadline;

    public HttpCustomerProfileClient(IntegrationClient client, Duration deadline) {
        this.client = client;
        this.deadline = deadline;
    }

    @Override
    public Optional<CustomerProfile> fetch(String tenantId, String customerId) {
        String path = "/crm/v1/tenants/" + enc(tenantId) + "/customers/" + enc(customerId);
        try {
            ProfileDto p = client.get(GET_PROFILE, path, ProfileDto.class, ProfileDto::valid, deadline);
            return Optional.of(new CustomerProfile(p.customerId(), p.segment(), p.homeCountry(), p.tenureDays(),
                    p.avgAmount90d(), p.riskTier(), p.boundDeviceIds() == null ? Set.of() : Set.copyOf(p.boundDeviceIds()), false));
        } catch (IntegrationException e) {
            if (e.kind() != IntegrationException.Kind.NOT_FOUND) {
                log.warn("profile lookup failed: {}", e.getMessage());
            }
            return Optional.empty();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Override
    public void warmUp() {
        client.warmUp("/actuator/health");
    }
}
