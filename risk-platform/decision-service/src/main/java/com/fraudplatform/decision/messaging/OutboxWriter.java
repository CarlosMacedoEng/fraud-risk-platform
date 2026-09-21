package com.fraudplatform.decision.messaging;

import com.fraudplatform.commons.correlation.Correlation;
import com.fraudplatform.commons.events.EventEnvelope;
import com.fraudplatform.commons.events.EventType;
import com.fraudplatform.decision.application.AdminEvents;
import com.fraudplatform.decision.application.DecisionEventsWriter;
import com.fraudplatform.decision.application.DomainEvents;
import com.fraudplatform.decision.domain.Decision;
import com.fraudplatform.decision.domain.Reason;
import com.fraudplatform.decision.domain.RiskDecision;
import com.fraudplatform.decision.domain.Transaction;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox writer (ADR-002). Every domain event is inserted into {@code outbox_events}
 * <em>inside the caller's database transaction</em>, so the event exists if and only if the state change
 * committed. The relay publishes it later; a broker outage never loses events or slows the caller.
 */
@Component
public class OutboxWriter implements DecisionEventsWriter, AdminEvents, DomainEvents {

    static final String PRODUCER = "decision-service";

    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    private void append(EventType type, String tenant, String aggregateType, String aggregateId, String partitionKey,
                        Map<String, Object> payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // Guard against a future refactoring that would silently reintroduce dual writes.
            throw new IllegalStateException("outbox events must be written inside the business transaction");
        }
        UUID id = UUID.randomUUID();
        String correlation = MDC.get(Correlation.MDC_CORRELATION_ID);
        EventEnvelope env = new EventEnvelope(id, type.name(), type.version(), Instant.now(), tenant, partitionKey,
                correlation, PRODUCER, payload);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("eventId", id.toString());
        headers.put("eventType", type.name());
        headers.put("tenantId", tenant);
        if (correlation != null) headers.put(Correlation.HEADER, correlation);
        outbox.insert(id, tenant, aggregateType, aggregateId, type.name(), type.version(), type.topic(), partitionKey,
                json.writeValueAsString(env), json.writeValueAsString(headers));
    }

    // ------------------------------------------------------------------ decisions

    @Override
    public void write(Transaction t, RiskDecision d) {
        Map<String, Object> received = new LinkedHashMap<>();
        received.put("transactionId", t.transactionId());
        received.put("customerId", t.customerId());
        received.put("accountId", t.accountId());
        received.put("eventTime", t.eventTime().toString());
        received.put("transactionType", t.type().name());
        received.put("channel", t.channel().name());
        received.put("amount", t.amount());
        received.put("currency", t.currency());
        received.put("merchantId", t.merchantId());
        received.put("mcc", t.mcc());
        received.put("beneficiaryId", t.beneficiaryId());
        received.put("source", "REALTIME");
        append(EventType.TransactionReceived, t.tenantId(), "transaction", t.transactionId(), t.customerId(), received);

        List<String> codes = d.reasons().stream().map(Reason::code).map(Enum::name).distinct().toList();
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("decisionId", d.decisionId().toString());
        created.put("transactionId", d.transactionId());
        created.put("customerId", t.customerId());
        created.put("decision", d.decision().name());
        created.put("riskScore", d.riskScore());
        created.put("riskLevel", d.riskLevel().name());
        created.put("modelProbability", d.modelProbability());
        created.put("modelVersion", d.modelVersion());
        created.put("strategyVersion", d.strategyVersion());
        created.put("reasonCodes", codes);
        created.put("degradedModes", d.degradedModes().stream().map(Enum::name).sorted().toList());
        created.put("amount", t.amount());
        created.put("channel", t.channel().name());
        append(EventType.RiskDecisionCreated, t.tenantId(), "decision", d.decisionId().toString(), t.customerId(), created);

        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("decisionId", d.decisionId().toString());
        outcome.put("transactionId", d.transactionId());
        outcome.put("customerId", t.customerId());
        if (d.decision() == Decision.APPROVE) {
            append(EventType.TransactionApproved, t.tenantId(), "decision", d.decisionId().toString(), t.customerId(), outcome);
        } else if (d.decision() == Decision.DECLINE) {
            outcome.put("reasonCodes", codes);
            append(EventType.TransactionDeclined, t.tenantId(), "decision", d.decisionId().toString(), t.customerId(), outcome);
        }
    }

    // ------------------------------------------------------------------ configuration & models

    @Override
    public void configurationChanged(String tenant, String environment, String action, String version, String previousVersion,
                                     Integer rolloutPercentage, String actor, String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("environment", environment);
        p.put("action", action);
        p.put("version", version);
        p.put("previousVersion", previousVersion);
        p.put("rolloutPercentage", rolloutPercentage);
        p.put("actor", actor);
        p.put("reason", reason);
        append(EventType.ConfigurationChanged, tenant, "strategy-deployment", tenant + "/" + environment, tenant, p);
    }

    @Override
    public void modelStatusChanged(String tenant, String modelVersion, String fromStatus, String toStatus, String actor, String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("modelVersion", modelVersion);
        p.put("fromStatus", fromStatus);
        p.put("toStatus", toStatus);
        p.put("actor", actor);
        p.put("reason", reason);
        append(EventType.ModelVersionPromoted, tenant, "model", modelVersion, tenant, p);
    }

    // ------------------------------------------------------------------ cases & labels

    @Override
    public void caseCreated(String tenant, UUID caseId, UUID decisionId, String transactionId, String customerId,
                            String priority, String externalCaseRef) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("caseId", caseId.toString());
        p.put("decisionId", decisionId.toString());
        p.put("transactionId", transactionId);
        p.put("customerId", customerId);
        p.put("priority", priority);
        p.put("externalCaseRef", externalCaseRef);
        append(EventType.CaseCreated, tenant, "case", caseId.toString(), customerId, p);
    }

    @Override
    public void fraudConfirmed(String tenant, String transactionId, String customerId, String label, String source,
                               String fraudType, UUID caseId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("transactionId", transactionId);
        p.put("customerId", customerId);
        p.put("label", label);
        p.put("source", source);
        p.put("fraudType", fraudType);
        p.put("caseId", caseId == null ? null : caseId.toString());
        append(EventType.FraudConfirmed, tenant, "label", transactionId, customerId, p);
    }
}
