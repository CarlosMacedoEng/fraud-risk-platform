package com.fraudplatform.decision.persistence;

import com.fraudplatform.decision.domain.CustomerProfile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class CustomerRepository {

    private final JdbcClient jdbc;

    public CustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CustomerProfile> find(String tenantId, String customerId) {
        return jdbc.sql("""
                        SELECT customer_id, segment, home_country, tenure_days, avg_amount_90d, risk_tier, bound_device_ids
                        FROM customers WHERE tenant_id = ? AND customer_id = ?
                        """)
                .params(tenantId, customerId)
                .query((rs, n) -> {
                    Array arr = rs.getArray("bound_device_ids");
                    Set<String> devices = arr == null ? Set.of() : Set.copyOf(Arrays.asList((String[]) arr.getArray()));
                    return new CustomerProfile(rs.getString("customer_id"), rs.getString("segment"),
                            rs.getString("home_country"), rs.getInt("tenure_days"), rs.getDouble("avg_amount_90d"),
                            rs.getString("risk_tier"), devices, false);
                })
                .optional();
    }

    /** Idempotent upsert used by profile ingestion (file or event). */
    public void upsert(String tenantId, CustomerProfile p, String source) {
        jdbc.sql("""
                        INSERT INTO customers (tenant_id, customer_id, segment, home_country, tenure_days, avg_amount_90d,
                                               risk_tier, bound_device_ids, source, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::text[], ?, now())
                        ON CONFLICT (tenant_id, customer_id) DO UPDATE SET
                            segment = EXCLUDED.segment, home_country = EXCLUDED.home_country,
                            tenure_days = EXCLUDED.tenure_days, avg_amount_90d = EXCLUDED.avg_amount_90d,
                            risk_tier = EXCLUDED.risk_tier, bound_device_ids = EXCLUDED.bound_device_ids,
                            source = EXCLUDED.source, updated_at = now()
                        """)
                .params(tenantId, p.customerId(), p.segment(), p.homeCountry(), p.tenureDays(), p.avgAmount90d(),
                        p.riskTier(), "{" + String.join(",", p.boundDeviceIds()) + "}", source)
                .update();
    }

    public int count(String tenantId) {
        return jdbc.sql("SELECT count(*) FROM customers WHERE tenant_id = ?").param(tenantId).query(Integer.class).single();
    }

    public List<String> sampleCustomerIds(String tenantId, int limit) {
        return jdbc.sql("SELECT customer_id FROM customers WHERE tenant_id = ? ORDER BY customer_id LIMIT ?")
                .params(tenantId, limit).query(String.class).list();
    }
}
