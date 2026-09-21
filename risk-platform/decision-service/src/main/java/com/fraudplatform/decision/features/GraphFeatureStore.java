package com.fraudplatform.decision.features;

import com.fraudplatform.decision.domain.Transaction;

public interface GraphFeatureStore {

    GraphRisk lookup(Transaction tx);
}
