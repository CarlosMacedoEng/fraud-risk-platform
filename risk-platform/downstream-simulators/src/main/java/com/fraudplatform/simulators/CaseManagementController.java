package com.fraudplatform.simulators;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulated case-management system. Case creation is idempotent on the client's {@code Idempotency-Key}
 * (the decision ID): a retry after a timeout returns the same case instead of opening a duplicate.
 */
@RestController
@RequestMapping("/cases/v1/cases")
class CaseManagementController {

    record CreateCase(String tenantId, String decisionId, String transactionId, String customerId, String priority,
                      List<String> reasons) {
    }

    record CaseView(String caseReference, String status, String decisionId, String priority, Instant createdAt) {
    }

    private final Map<String, CaseView> byKey = new ConcurrentHashMap<>();
    private final Map<String, CaseView> byRef = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);

    @PostMapping
    ResponseEntity<CaseView> create(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                    @RequestBody CreateCase req) {
        if (key == null || req.decisionId() == null || req.priority() == null) {
            return ResponseEntity.badRequest().build();
        }
        CaseView existing = byKey.get(key);
        if (existing != null) return ResponseEntity.ok(existing);
        CaseView created = new CaseView("CM-" + sequence.incrementAndGet(), "OPEN", req.decisionId(), req.priority(), Instant.now());
        CaseView raced = byKey.putIfAbsent(key, created);
        if (raced != null) return ResponseEntity.ok(raced);
        byRef.put(created.caseReference(), created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{ref}")
    ResponseEntity<CaseView> get(@PathVariable String ref) {
        CaseView c = byRef.get(ref);
        return c == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(c);
    }

    @GetMapping
    Map<String, Object> stats() {
        return Map.of("cases", byRef.size());
    }
}
