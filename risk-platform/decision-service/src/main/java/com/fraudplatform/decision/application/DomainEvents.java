package com.fraudplatform.decision.application;

import java.util.UUID;

/** Case and label events, written to the outbox inside the calling transaction (Stage 6 implementation). */
public interface DomainEvents {

    void caseCreated(String tenant, UUID caseId, UUID decisionId, String transactionId, String customerId, String priority,
                     String externalCaseRef);

    void fraudConfirmed(String tenant, String transactionId, String customerId, String label, String source,
                        String fraudType, UUID caseId);
}
