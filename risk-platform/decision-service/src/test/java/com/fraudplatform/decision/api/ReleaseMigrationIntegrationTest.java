package com.fraudplatform.decision.api;

import com.fraudplatform.decision.IntegrationTestBase;
import com.fraudplatform.decision.domain.CustomerProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Release 2.0 migration (docs/MIGRATION_AND_UPGRADE_RUNBOOK.md): V6 expand, online backfill, reconciliation,
 * and a dry run of the pending V7 contract step.
 */
class ReleaseMigrationIntegrationTest extends IntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;

    private List<String> score(int n) {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            String customer = "ALD-MIG-" + i;
            customers.upsert("aldermoor-bank", new CustomerProfile(customer, "retail", "PT", 500, 40, "standard", Set.of(), false), "BATCH");
            String id = "MIG-" + UUID.randomUUID();
            http.post().uri("/v1/decisions").header("X-Api-Key", GATEWAY_KEY).header("Idempotency-Key", id)
                    .body(cardPayment(id, customer, 30 + i, "D-MIG-" + i, "PT", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()))
                    .retrieve().toEntity(String.class);
            ids.add(id);
        }
        return ids;
    }

    private JsonNode admin(String method, String path) {
        var spec = method.equals("GET") ? http.get().uri("/v1/admin/tenants/aldermoor-bank" + path)
                : http.post().uri("/v1/admin/tenants/aldermoor-bank" + path);
        return JSON.readTree(spec.header("X-Api-Key", ADMIN_KEY).retrieve().body(String.class));
    }

    @Test
    void newDecisionsCarryTheChannel() {
        List<String> ids = score(3);
        List<String> channels = jdbc.queryForList(
                "SELECT channel FROM risk_decisions WHERE tenant_id = 'aldermoor-bank' AND transaction_id = ANY (?)",
                String.class, (Object) ids.toArray(String[]::new));
        assertThat(channels).hasSize(3).containsOnly("ECOM");
    }

    @Test
    void backfillFillsHistoricalRowsAndReconciliationGatesTheContractStep() throws Exception {
        List<String> ids = score(5);
        // Simulate decisions written by release 1.x (before V6): no channel.
        int nulled = jdbc.update("UPDATE risk_decisions SET channel = NULL WHERE tenant_id = 'aldermoor-bank' AND transaction_id = ANY (?)",
                (Object) ids.toArray(String[]::new));
        assertThat(nulled).isEqualTo(5);

        JsonNode before = admin("GET", "/migrations/decision-channel");
        assertThat(before.get("remaining").asLong()).isGreaterThanOrEqualTo(5);
        assertThat(before.get("readyForContract").asBoolean()).isFalse();

        JsonNode run = admin("POST", "/migrations/decision-channel/backfill?batchSize=100&pauseMs=0");
        assertThat(run.get("updated").asLong()).isGreaterThanOrEqualTo(5);
        assertThat(run.get("complete").asBoolean()).isTrue();
        JsonNode after = run.get("status");
        assertThat(after.get("remaining").asLong()).isZero();
        assertThat(after.get("mismatched").asLong()).isZero();
        assertThat(after.get("readyForContract").asBoolean()).isTrue();

        // Idempotent: a second run changes nothing.
        assertThat(admin("POST", "/migrations/decision-channel/backfill?batchSize=100&pauseMs=0").get("updated").asLong()).isZero();

        // Dry run of the pending V7 contract step (other tenants are backfilled too, then rolled back with the DDL).
        String contract = Files.readString(Path.of("src/main/resources/db/pending/V7__decision_channel_contract.sql"));
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE risk_decisions d SET channel = t.channel FROM transactions t "
                    + "WHERE d.channel IS NULL AND t.tenant_id = d.tenant_id AND t.transaction_id = d.transaction_id");
            for (String stmt : contract.replaceAll("(?m)^--.*$", "").split(";")) {
                if (!stmt.isBlank()) jdbc.execute(stmt);
            }
            String nullable = jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                    + "WHERE table_name = 'risk_decisions' AND column_name = 'channel'", String.class);
            assertThat(nullable).isEqualTo("NO");
            s.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'risk_decisions' AND column_name = 'channel'", String.class)).isEqualTo("YES");
    }
}
