package com.fraudplatform.commons.events;

/**
 * Event catalogue. Topic names carry the major schema version: a breaking change creates a new topic
 * (e.g. {@code fraud.decisions.v2}) that is published alongside v1 until consumers have migrated.
 * Additive changes (new optional fields) keep the version.
 */
public enum EventType {
    TransactionReceived(Topics.TRANSACTIONS, 1),
    RiskDecisionCreated(Topics.DECISIONS, 1),
    TransactionApproved(Topics.DECISIONS, 1),
    TransactionDeclined(Topics.DECISIONS, 1),
    CaseCreated(Topics.CASES, 1),
    FraudConfirmed(Topics.LABELS, 1),
    ConfigurationChanged(Topics.CONFIG, 1),
    ModelVersionPromoted(Topics.CONFIG, 1);

    private final String topic;
    private final int version;

    EventType(String topic, int version) {
        this.topic = topic;
        this.version = version;
    }

    public String topic() {
        return topic;
    }

    public int version() {
        return version;
    }

    public static final class Topics {
        public static final String TRANSACTIONS = "fraud.transactions.v1";
        public static final String DECISIONS = "fraud.decisions.v1";
        public static final String CASES = "fraud.cases.v1";
        public static final String LABELS = "fraud.labels.v1";
        public static final String CONFIG = "platform.config.v1";

        /** Dead-letter topic of a consumer group on a source topic. */
        public static String deadLetter(String topic, String consumerGroup) {
            return topic + "." + consumerGroup + ".dlt";
        }

        private Topics() {
        }
    }
}
