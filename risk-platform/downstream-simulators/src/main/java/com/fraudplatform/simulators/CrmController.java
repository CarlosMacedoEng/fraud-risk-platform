package com.fraudplatform.simulators;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Simulated CRM customer-profile API (v1). Returns a deterministic synthetic profile for any customer ID
 * with the tenant's prefix (ALD- / QPY-) and 404 otherwise — enough to exercise lookup, not-found and
 * failure paths without real customer data.
 */
@RestController
@RequestMapping("/crm/v1/tenants/{tenant}/customers")
class CrmController {

    record Profile(String customerId, String segment, String homeCountry, int tenureDays, double avgAmount90d,
                   String riskTier, List<String> boundDeviceIds) {
    }

    private static final Map<String, String> PREFIX = Map.of("aldermoor-bank", "ALD-", "quillon-pay", "QPY-");
    private static final Map<String, String> HOME = Map.of("aldermoor-bank", "PT", "quillon-pay", "ES");

    @GetMapping("/{customerId}")
    ResponseEntity<Profile> get(@PathVariable String tenant, @PathVariable String customerId) {
        String prefix = PREFIX.get(tenant);
        if (prefix == null || !customerId.startsWith(prefix) || customerId.contains("UNKNOWN")) {
            return ResponseEntity.notFound().build();
        }
        int h = Math.floorMod(customerId.hashCode(), 1000);
        String segment = h < 150 ? "student" : h < 750 ? "retail" : h < 900 ? "premium" : "business";
        int tenure = 30 + h * 3;
        return ResponseEntity.ok(new Profile(customerId, segment, HOME.get(tenant), tenure, 20 + (h % 150),
                tenure < 90 ? "elevated" : "standard", List.of("D-CRM-" + h)));
    }
}
