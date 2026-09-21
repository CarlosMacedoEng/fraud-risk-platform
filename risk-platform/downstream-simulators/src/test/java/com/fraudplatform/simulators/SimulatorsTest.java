package com.fraudplatform.simulators;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimulatorsTest {

    @DynamicPropertySource
    static void feed(DynamicPropertyRegistry r) throws Exception {
        Path f = Files.createTempFile("feed", ".txt");
        Files.writeString(f, "# test feed\n66.6.6.6\n");
        r.add("simulators.threat-feed", f::toString);
    }

    @Autowired
    Environment env;

    @Autowired
    FaultRegistry faults;

    RestClient http;

    @BeforeEach
    void client() {
        http = RestClient.builder().baseUrl("http://localhost:" + env.getProperty("local.server.port"))
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(s -> true, (req, res) -> { }).build();
    }

    @AfterEach
    void clear() {
        faults.clear();
    }

    @Test
    void crmReturnsDeterministicProfilesAndNotFound() {
        var a = http.get().uri("/crm/v1/tenants/aldermoor-bank/customers/ALD-C000123").retrieve().body(String.class);
        var b = http.get().uri("/crm/v1/tenants/aldermoor-bank/customers/ALD-C000123").retrieve().body(String.class);
        assertThat(a).isEqualTo(b).contains("\"homeCountry\":\"PT\"");
        var missing = http.get().uri("/crm/v1/tenants/aldermoor-bank/customers/XYZ-1").retrieve().toEntity(String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void caseCreationIsIdempotentOnTheIdempotencyKey() {
        String body = "{\"tenantId\":\"aldermoor-bank\",\"decisionId\":\"d-1\",\"transactionId\":\"t-1\",\"customerId\":\"c\",\"priority\":\"HIGH\",\"reasons\":[]}";
        var first = http.post().uri("/cases/v1/cases").header("Idempotency-Key", "d-1").body(body).retrieve().toEntity(String.class);
        var second = http.post().uri("/cases/v1/cases").header("Idempotency-Key", "d-1").body(body).retrieve().toEntity(String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody()).contains(first.getBody().substring(first.getBody().indexOf("CM-"), first.getBody().indexOf("CM-") + 7));
    }

    @Test
    void deviceIntelFlagsThreatFeedIps() {
        String res = http.post().uri("/device-intel/v1/assessments")
                .body("{\"tenantId\":\"t\",\"customerId\":\"c\",\"deviceId\":\"D1\",\"ipAddress\":\"66.6.6.6\",\"ipCountry\":\"PT\"}")
                .retrieve().body(String.class);
        assertThat(res).contains("\"compromisedIp\":true").contains("IP_ON_THREAT_FEED");
    }

    @Test
    void faultInjectionAffectsOnlyTheTargetedSystem() {
        http.post().uri("/admin/faults/crm").body("{\"latencyMs\":0,\"errorRate\":1.0,\"errorStatus\":503,\"malformed\":false}")
                .retrieve().toBodilessEntity();
        assertThat(http.get().uri("/crm/v1/tenants/aldermoor-bank/customers/ALD-1").retrieve().toEntity(String.class)
                .getStatusCode().value()).isEqualTo(503);
        assertThat(http.post().uri("/device-intel/v1/assessments").body("{\"deviceId\":\"D1\"}").retrieve()
                .toEntity(String.class).getStatusCode().value()).isEqualTo(200);
    }
}
