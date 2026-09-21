package com.fraudplatform.decision.features;

import com.fraudplatform.decision.domain.Transaction;

/** Online state for velocity and "seen" features. Implementations: Redis, in-memory, PostgreSQL fallback. */
public interface FeatureStore {

    /** Read the state needed to compute features for {@code tx}; must not include {@code tx} itself. */
    EntityState load(Transaction tx);

    /** Fold {@code tx} into the state. Called once per transaction, after the decision is persisted. */
    void record(Transaction tx);
}
