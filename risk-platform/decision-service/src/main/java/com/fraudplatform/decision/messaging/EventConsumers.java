package com.fraudplatform.decision.messaging;

import com.fraudplatform.commons.correlation.Correlation;
import com.fraudplatform.commons.events.EventType;
import com.fraudplatform.commons.integration.IntegrationException;
import com.fraudplatform.decision.application.ActiveStrategyProvider;
import com.fraudplatform.decision.application.CaseService;
import com.fraudplatform.decision.persistence.CaseRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Event consumers inside decision-service. Every handler is idempotent: de-duplication on eventId via
 * {@code processed_events}, plus naturally idempotent effects (unique case per decision, label upsert).
 */
@Component
@ConditionalOnProperty(name = "platform.messaging.enabled", havingValue = "true")
public class EventConsumers {

    private static final Logger log = LoggerFactory.getLogger(EventConsumers.class);

    private final ObjectMapper json;
    private final ProcessedEvents processed;
    private final CaseService cases;
    private final CaseRepository caseRepository;
    private final ActiveStrategyProvider strategies;
    private final MeterRegistry meters;

    public EventConsumers(ObjectMapper json, ProcessedEvents processed, CaseService cases, CaseRepository caseRepository,
                          ActiveStrategyProvider strategies, MeterRegistry meters) {
        this.json = json;
        this.processed = processed;
        this.cases = cases;
        this.caseRepository = caseRepository;
        this.strategies = strategies;
        this.meters = meters;
    }

    private JsonNode envelope(ConsumerRecord<String, String> record) {
        JsonNode env = json.readTree(record.value());   // JacksonException -> non-retryable -> DLT
        if (!env.hasNonNull("eventId") || !env.hasNonNull("eventType") || !env.has("payload")) {
            throw new NonRetryableEventException("envelope missing required fields", null);
        }
        if (env.hasNonNull("correlationId")) MDC.put(Correlation.MDC_CORRELATION_ID, env.get("correlationId").asString());
        MDC.put(Correlation.MDC_TENANT_ID, env.path("tenantId").asString());
        return env;
    }

    /** REVIEW decisions become investigation cases (asynchronous REST integration with case management). */
    @KafkaListener(topics = EventType.Topics.DECISIONS, groupId = MessagingConfig.CASE_CREATOR, containerFactory = "caseCreatorFactory")
    public void onDecision(ConsumerRecord<String, String> record) {
        try {
            JsonNode env = envelope(record);
            if (!EventType.RiskDecisionCreated.name().equals(env.get("eventType").asString())) return;
            JsonNode p = env.get("payload");
            if (!"REVIEW".equals(p.path("decision").asString())) return;
            UUID eventId = UUID.fromString(env.get("eventId").asString());
            if (processed.alreadyProcessed(MessagingConfig.CASE_CREATOR, eventId)) {
                meters.counter("risk.consumer.duplicates", "group", MessagingConfig.CASE_CREATOR).increment();
                return;
            }
            List<String> reasons = new ArrayList<>();
            p.path("reasonCodes").forEach(r -> reasons.add(r.asString()));
            try {
                cases.openForReviewDecision(env.get("tenantId").asString(), UUID.fromString(p.get("decisionId").asString()),
                        p.get("transactionId").asString(), p.get("customerId").asString(), p.path("riskLevel").asString(), reasons);
            } catch (IntegrationException e) {
                if (!e.kind().retryable()) throw new NonRetryableEventException("case management rejected the case", e);
                throw e;   // transient: retried with back-off, then DLT
            }
            processed.markProcessed(MessagingConfig.CASE_CREATOR, eventId);
        } finally {
            MDC.clear();
        }
    }

    /** External labels (chargebacks, customer reports, batch labels) → fraud_labels for training and reporting. */
    @KafkaListener(topics = EventType.Topics.LABELS, groupId = MessagingConfig.LABEL_INGESTOR, containerFactory = "labelIngestorFactory")
    public void onLabel(ConsumerRecord<String, String> record) {
        try {
            JsonNode env = envelope(record);
            if (!EventType.FraudConfirmed.name().equals(env.get("eventType").asString())) return;
            JsonNode p = env.get("payload");
            if ("ANALYST".equals(p.path("source").asString())) return;   // already stored by the case flow
            UUID eventId = UUID.fromString(env.get("eventId").asString());
            if (!processed.markProcessed(MessagingConfig.LABEL_INGESTOR, eventId)) return;
            if (!p.hasNonNull("transactionId") || !p.hasNonNull("label")) {
                throw new NonRetryableEventException("label without transactionId/label", null);
            }
            caseRepository.insertLabel(env.get("tenantId").asString(), p.get("transactionId").asString(),
                    p.path("source").asString("BATCH_LABEL"), p.get("label").asString(),
                    p.hasNonNull("fraudType") ? p.get("fraudType").asString() : null,
                    Instant.parse(env.get("occurredAt").asString()));
        } finally {
            MDC.clear();
        }
    }

    /**
     * Broadcast: every instance has its own group (random suffix, latest offset) so all instances refresh
     * their strategy cache immediately after a promotion or rollback.
     */
    @KafkaListener(topics = EventType.Topics.CONFIG, groupId = "config-refresh-#{T(java.util.UUID).randomUUID()}",
            containerFactory = "broadcastFactory", properties = {"auto.offset.reset=latest"})
    public void onConfiguration(ConsumerRecord<String, String> record) {
        try {
            JsonNode env = envelope(record);
            if (EventType.ConfigurationChanged.name().equals(env.get("eventType").asString())
                    && strategies.environment().equals(env.get("payload").path("environment").asString())) {
                strategies.refresh(env.get("tenantId").asString());
                log.info("strategy cache refreshed from ConfigurationChanged tenant={}", env.get("tenantId").asString());
            }
        } finally {
            MDC.clear();
        }
    }
}
