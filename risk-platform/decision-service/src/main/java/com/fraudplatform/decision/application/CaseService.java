package com.fraudplatform.decision.application;

import com.fraudplatform.commons.correlation.Correlation;
import com.fraudplatform.commons.error.ErrorCode;
import com.fraudplatform.commons.error.PlatformException;
import com.fraudplatform.commons.integration.IntegrationException;
import com.fraudplatform.decision.integration.CaseManagementClient;
import com.fraudplatform.decision.persistence.AuditRepository;
import com.fraudplatform.decision.persistence.CaseRepository;
import com.fraudplatform.decision.persistence.CaseRepository.FraudCase;
import com.fraudplatform.decision.persistence.IntegrationFailureRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Review decisions become investigation cases in the customer's case-management system.
 *
 * <p>Flow: local case row (idempotent on decision ID) → external API call with {@code Idempotency-Key =
 * decisionId} → mark OPEN + {@code CaseCreated} event. The external call happens <em>outside</em> any DB
 * transaction (never hold a connection while waiting on a remote system). Failures are recorded in
 * {@code integration_failures} and rethrown so the event consumer retries; a scheduled reconciliation
 * re-dispatches cases left in PENDING_EXTERNAL (e.g. consumer down, events lost).
 */
@Service
public class CaseService {

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);
    private static final Set<String> OUTCOMES = Set.of("CONFIRMED_FRAUD", "FALSE_POSITIVE");

    private final CaseRepository cases;
    private final Optional<CaseManagementClient> client;
    private final IntegrationFailureRepository failures;
    private final AuditRepository audit;
    private final DomainEvents events;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;

    public CaseService(CaseRepository cases, Optional<CaseManagementClient> client, IntegrationFailureRepository failures,
                       AuditRepository audit, DomainEvents events, TransactionTemplate tx, MeterRegistry meters) {
        this.cases = cases;
        this.client = client;
        this.failures = failures;
        this.audit = audit;
        this.events = events;
        this.tx = tx;
        this.meters = meters;
    }

    public static String priorityFor(String riskLevel) {
        return switch (riskLevel == null ? "" : riskLevel) {
            case "CRITICAL" -> "CRITICAL";
            case "HIGH" -> "HIGH";
            case "MEDIUM" -> "MEDIUM";
            default -> "LOW";
        };
    }

    /** Idempotent: safe to call for duplicate or replayed events. */
    public FraudCase openForReviewDecision(String tenant, UUID decisionId, String transactionId, String customerId,
                                           String riskLevel, List<String> reasons) {
        cases.insertIfAbsent(UUID.randomUUID(), tenant, decisionId, transactionId, customerId, priorityFor(riskLevel));
        FraudCase c = cases.findByDecision(decisionId).orElseThrow();
        if (!"PENDING_EXTERNAL".equals(c.status())) {
            meters.counter("risk.cases.duplicate_events").increment();
            return c;   // already opened (duplicate delivery)
        }
        return dispatch(c, reasons);
    }

    FraudCase dispatch(FraudCase c, List<String> reasons) {
        String externalRef = null;
        if (client.isPresent()) {
            try {
                externalRef = client.get().create(c.tenantId(), c.decisionId().toString(), c.transactionId(), c.customerId(),
                        c.priority(), reasons).caseReference();
            } catch (IntegrationException e) {
                failures.record(c.tenantId(), "case-management", "create-case", e.kind().name(), e.getMessage(),
                        c.decisionId().toString(), MDC.get(Correlation.MDC_CORRELATION_ID), 0);
                meters.counter("risk.cases.dispatch_failures", "kind", e.kind().name()).increment();
                throw e;
            }
        }
        String ref = externalRef;
        tx.executeWithoutResult(s -> {
            if (cases.markOpened(c.caseId(), ref) == 1) {
                events.caseCreated(c.tenantId(), c.caseId(), c.decisionId(), c.transactionId(), c.customerId(), c.priority(), ref);
            }
        });
        if (ref != null) failures.resolve("case-management", c.decisionId().toString());
        meters.counter("risk.cases.opened", "tenant", c.tenantId()).increment();
        log.info("case opened caseId={} decisionId={} externalRef={}", c.caseId(), c.decisionId(), ref);
        return cases.findByDecision(c.decisionId()).orElseThrow();
    }

    /** Reconciliation safety net: cases stuck in PENDING_EXTERNAL for more than 30 s are re-dispatched. */
    @Scheduled(fixedDelayString = "${platform.cases.reconcile-ms:60000}", initialDelay = 30000)
    public void reconcilePending() {
        for (FraudCase c : cases.pendingExternal(50)) {
            try {
                dispatch(c, List.of("RECONCILIATION_RETRY"));
                meters.counter("risk.cases.reconciled").increment();
            } catch (IntegrationException e) {
                log.warn("case reconciliation failed caseId={}: {}", c.caseId(), e.getMessage());
            }
        }
    }

    public List<FraudCase> queue(String tenant, String status, int limit) {
        return cases.queue(tenant, status, Math.min(limit, 500));
    }

    public FraudCase get(String tenant, UUID caseId) {
        return cases.find(tenant, caseId).orElseThrow(() -> new PlatformException(ErrorCode.NOT_FOUND, "case not found"));
    }

    /** Analyst outcome → label (feedback loop) + FraudConfirmed event, atomically. */
    public FraudCase resolve(String tenant, UUID caseId, String outcome, String fraudType, String note, String actor, int rowVersion) {
        if (!OUTCOMES.contains(outcome)) {
            throw new PlatformException(ErrorCode.VALIDATION_FAILED, "outcome must be one of " + OUTCOMES);
        }
        FraudCase c = get(tenant, caseId);
        tx.executeWithoutResult(s -> {
            if (cases.resolve(tenant, caseId, outcome, note, actor, rowVersion) != 1) {
                throw new PlatformException(ErrorCode.VERSION_CONFLICT, "case was modified or is already closed",
                        Map.of("currentRowVersion", get(tenant, caseId).rowVersion()));
            }
            String label = outcome.equals("CONFIRMED_FRAUD") ? "FRAUD" : "GENUINE";
            cases.insertLabel(tenant, c.transactionId(), "ANALYST", label, fraudType, Instant.now());
            audit.record(tenant, actor, "CASE_RESOLVED", "case", caseId.toString(), "{\"status\":\"" + c.status() + "\"}",
                    "{\"status\":\"" + outcome + "\"}", MDC.get(Correlation.MDC_CORRELATION_ID));
            events.fraudConfirmed(tenant, c.transactionId(), c.customerId(), label, "ANALYST", fraudType, caseId);
        });
        return get(tenant, caseId);
    }
}
