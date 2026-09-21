package com.fraudplatform.decision.features;

import com.fraudplatform.decision.IntegrationTestBase;
import com.fraudplatform.decision.domain.Transaction;
import com.fraudplatform.decision.persistence.DecisionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PostgreSQL fallback must compute the same features as Redis/Python while Redis is down.
 * (Its "seen" sets are limited to 180 days; the 3-day parity stream is well inside that.)
 */
class JdbcFallbackParityTest extends IntegrationTestBase {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DecisionRepository decisions;

    @Test
    void postgresFallbackMatchesPythonFeatures() throws Exception {
        jdbc.sql("INSERT INTO tenants (tenant_id, display_name) VALUES ('parity-test', 'parity') ON CONFLICT DO NOTHING").update();
        jdbc.sql("DELETE FROM transactions WHERE tenant_id = 'parity-test'").update();
        JdbcFallbackFeatureStore db = new JdbcFallbackFeatureStore(jdbc, 1);
        // Wrap: load from PostgreSQL, "record" = persist the transaction row (what the decision flow does).
        FeatureStore store = new FeatureStore() {
            @Override
            public EntityState load(Transaction tx) {
                return db.load(retenant(tx));
            }

            @Override
            public void record(Transaction tx) {
                decisions.insertTransaction(retenant(tx), "BATCH");
            }
        };
        List<String> mismatches = FeatureParityTest.replay("aldermoor-bank", store);
        assertThat(mismatches).isEmpty();
    }

    private static Transaction retenant(Transaction t) {
        return new Transaction("parity-test", t.transactionId(), t.customerId(), t.accountId(), t.eventTime(), t.type(),
                t.channel(), t.amount(), t.currency(), t.cardToken(), t.merchantId(), t.mcc(), t.merchantCountry(),
                t.beneficiaryId(), t.beneficiaryCountry(), t.deviceId(), t.ipAddress(), t.ipCountry());
    }
}
