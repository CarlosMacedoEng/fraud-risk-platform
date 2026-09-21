package com.fraudplatform.commons.integration;

import com.fraudplatform.commons.correlation.Correlation;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationClientTest {

    record Profile(String customerId, String segment) {
    }

    static final IntegrationClient.Operation LOOKUP = new IntegrationClient.Operation("lookup", true);
    static final IntegrationClient.Operation CREATE = new IntegrationClient.Operation("create", false);

    WireMockServer server;
    IntegrationClient client;
    SimpleMeterRegistry meters;

    @BeforeEach
    void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        meters = new SimpleMeterRegistry();
        client = new IntegrationClient(new IntegrationClient.Settings("crm", URI.create(server.baseUrl()),
                Duration.ofMillis(200), Duration.ofMillis(150), 3, Duration.ofMillis(10), 10, 50f, Duration.ofSeconds(30),
                Map.of("X-Client", "test")), JsonMapper.builder().build(), meters);
    }

    @AfterEach
    void stop() {
        server.stop();
        MDC.clear();
    }

    @Test
    void retriesTransientFailuresForIdempotentOperations() {
        server.stubFor(get("/p/1").inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("ok"));
        server.stubFor(get("/p/1").inScenario("flaky").whenScenarioStateIs("ok")
                .willReturn(okJson("{\"customerId\":\"1\",\"segment\":\"retail\"}")));
        Profile p = client.get(LOOKUP, "/p/1", Profile.class, x -> x.customerId() != null, Duration.ofSeconds(1));
        assertThat(p.segment()).isEqualTo("retail");
        server.verify(2, getRequestedFor(urlEqualTo("/p/1")));
    }

    @Test
    void neverRetriesNonIdempotentOperations() {
        server.stubFor(post("/cases").willReturn(aResponse().withStatus(503)));
        // Generous deadline: this test is about the retry policy, not timing. With 1 s it failed once as TIMEOUT
        // on a loaded laptop (journal J-39).
        assertThatThrownBy(() -> client.post(CREATE, "/cases", Map.of("a", 1), null, Profile.class, null, Duration.ofSeconds(5)))
                .isInstanceOf(IntegrationException.class)
                .extracting(e -> ((IntegrationException) e).kind()).isEqualTo(IntegrationException.Kind.SERVER_ERROR);
        server.verify(1, postRequestedFor(urlEqualTo("/cases")));
    }

    @Test
    void retriesStopAtTheDeadline() {
        server.stubFor(get("/slow").willReturn(okJson("{}").withFixedDelay(400)));
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.get(LOOKUP, "/slow", Profile.class, null, Duration.ofMillis(250)))
                .extracting(e -> ((IntegrationException) e).kind()).isEqualTo(IntegrationException.Kind.TIMEOUT);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(350);
    }

    @Test
    void notFoundAndClientErrorsAreTypedAndNotRetried() {
        server.stubFor(get("/p/missing").willReturn(aResponse().withStatus(404)));
        server.stubFor(get("/p/bad").willReturn(aResponse().withStatus(400).withBody("{\"error\":\"bad id\"}")));
        assertThatThrownBy(() -> client.get(LOOKUP, "/p/missing", Profile.class, null, Duration.ofSeconds(1)))
                .extracting(e -> ((IntegrationException) e).kind()).isEqualTo(IntegrationException.Kind.NOT_FOUND);
        assertThatThrownBy(() -> client.get(LOOKUP, "/p/bad", Profile.class, null, Duration.ofSeconds(1)))
                .extracting(e -> ((IntegrationException) e).kind()).isEqualTo(IntegrationException.Kind.CLIENT_ERROR);
        server.verify(1, getRequestedFor(urlEqualTo("/p/bad")));
    }

    @Test
    void contractViolationsAreDetected() {
        server.stubFor(get("/p/x").willReturn(okJson("{\"segment\":\"retail\"}")));
        server.stubFor(get("/p/y").willReturn(okJson("<html>maintenance</html>")));
        assertThatThrownBy(() -> client.get(LOOKUP, "/p/x", Profile.class, p -> p.customerId() != null, Duration.ofSeconds(1)))
                .extracting(e -> ((IntegrationException) e).kind()).isEqualTo(IntegrationException.Kind.CONTRACT_VIOLATION);
        assertThatThrownBy(() -> client.get(LOOKUP, "/p/y", Profile.class, null, Duration.ofSeconds(1)))
                .extracting(e -> ((IntegrationException) e).kind()).isEqualTo(IntegrationException.Kind.CONTRACT_VIOLATION);
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndFailsFast() {
        server.stubFor(get("/down").willReturn(aResponse().withStatus(500)));
        for (int i = 0; i < 12; i++) {
            try {
                client.get(new IntegrationClient.Operation("down", false), "/down", Profile.class, null, Duration.ofSeconds(1));
            } catch (IntegrationException ignored) {
            }
        }
        int calls = server.getAllServeEvents().size();
        assertThatThrownBy(() -> client.get(LOOKUP, "/down", Profile.class, null, Duration.ofSeconds(1)))
                .extracting(e -> ((IntegrationException) e).kind()).isEqualTo(IntegrationException.Kind.CIRCUIT_OPEN);
        assertThat(server.getAllServeEvents()).hasSize(calls);   // no network call when open
    }

    @Test
    void notFoundDoesNotOpenTheCircuit() {
        server.stubFor(get("/p/missing").willReturn(aResponse().withStatus(404)));
        for (int i = 0; i < 20; i++) {
            try {
                client.get(LOOKUP, "/p/missing", Profile.class, null, Duration.ofSeconds(1));
            } catch (IntegrationException ignored) {
            }
        }
        assertThat(client.circuitBreaker().getState().name()).isEqualTo("CLOSED");
    }

    @Test
    void propagatesCorrelationIdempotencyAndDefaultHeaders() {
        MDC.put(Correlation.MDC_CORRELATION_ID, "corr-123");
        server.stubFor(post("/cases").willReturn(okJson("{\"customerId\":\"c\",\"segment\":\"s\"}")));
        client.post(CREATE, "/cases", Map.of("ref", "d-1"), "d-1", Profile.class, null, Duration.ofSeconds(1));
        server.verify(postRequestedFor(urlEqualTo("/cases"))
                .withHeader(Correlation.HEADER, equalTo("corr-123"))
                .withHeader("Idempotency-Key", equalTo("d-1"))
                .withHeader("X-Client", equalTo("test")));
        assertThat(meters.find("integration.client.requests").tag("outcome", "success").timer()).isNotNull();
    }
}
