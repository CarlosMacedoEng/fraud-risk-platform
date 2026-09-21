package com.fraudplatform.decision.integration;

import com.fraudplatform.decision.domain.Transaction;

import java.util.Optional;

/** Outbound port to the device/identity-risk vendor. Empty = unavailable (the decision degrades, never fails). */
public interface DeviceRiskClient {

    record DeviceRisk(double riskScore, boolean compromisedIp, boolean emulator, boolean proxy) {
    }

    Optional<DeviceRisk> assess(Transaction transaction);
}
