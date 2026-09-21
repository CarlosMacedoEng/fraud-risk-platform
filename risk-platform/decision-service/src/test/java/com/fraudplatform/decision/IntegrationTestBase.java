package com.fraudplatform.decision;

import com.fraudplatform.decision.domain.CustomerProfile;
import com.fraudplatform.decision.persistence.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Set;

/**
 * Real PostgreSQL and Redis (Testcontainers), real models and strategies from the repository.
 * Containers are started once per JVM and shared by all integration test classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    public static final String GATEWAY_KEY = "dev-aldermoor-gateway-key";
    public static final String ANALYST_KEY = "dev-aldermoor-analyst-key";
    public static final String ADMIN_KEY = "dev-aldermoor-admin-key";
    public static final String QUILLON_GATEWAY_KEY = "dev-quillon-gateway-key";
    public static final String QUILLON_ADMIN_KEY = "dev-quillon-admin-key";

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("platform.models-dir", TestPaths.MODELS::toString);
        registry.add("platform.config-dir", TestPaths.CONFIG::toString);
    }

    @Autowired
    protected Environment env;

    @Autowired
    protected CustomerRepository customers;

    protected RestClient http;

    @BeforeEach
    void client() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + env.getProperty("local.server.port"))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(status -> true, (req, res) -> { })   // tests assert on status codes
                .build();
        customers.upsert("aldermoor-bank", new CustomerProfile("ALD-C000001", "retail", "PT", 800, 40.0, "standard",
                Set.of("D-KNOWN-1"), false), "BATCH");
        customers.upsert("quillon-pay", new CustomerProfile("QPY-C000001", "frequent", "ES", 300, 30.0, "standard",
                Set.of("D-Q-1"), false), "BATCH");
    }

    protected static String cardPayment(String txId, String customer, double amount, String device, String merchantCountry, String eventTime) {
        return """
                {"transactionId":"%s","customerId":"%s","accountId":"%s-ACC","eventTime":"%s",
                 "transactionType":"CARD_PAYMENT","channel":"ECOM","amount":%.2f,"currency":"EUR",
                 "cardToken":"tok_%s","merchantId":"M000123","mcc":"5732","merchantCountry":"%s",
                 "deviceId":"%s","ipAddress":"11.2.3.4","ipCountry":"PT"}
                """.formatted(txId, customer, customer, eventTime, amount, customer, merchantCountry, device);
    }

    protected static String transfer(String txId, String customer, double amount, String device, String beneficiary, String eventTime) {
        return """
                {"transactionId":"%s","customerId":"%s","accountId":"%s-ACC","eventTime":"%s",
                 "transactionType":"TRANSFER","channel":"MOBILE","amount":%.2f,"currency":"EUR",
                 "beneficiaryId":"%s","beneficiaryCountry":"PT","deviceId":"%s","ipAddress":"11.2.3.4","ipCountry":"PT"}
                """.formatted(txId, customer, customer, eventTime, amount, beneficiary, device);
    }
}
