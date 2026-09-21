package com.fraudplatform.decision.application;

import com.fraudplatform.decision.domain.RiskDecision;
import com.fraudplatform.decision.domain.Transaction;

/**
 * Writes domain events for a decision <em>inside</em> the decision's database transaction
 * (transactional outbox, ADR-002). Implemented by the messaging layer.
 */
public interface DecisionEventsWriter {

    void write(Transaction transaction, RiskDecision decision);
}
