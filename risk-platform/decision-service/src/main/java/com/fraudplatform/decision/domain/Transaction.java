package com.fraudplatform.decision.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** A payment or transfer to be scored. PAN is never received — only a card token. */
public record Transaction(
        String tenantId,
        String transactionId,
        String customerId,
        String accountId,
        Instant eventTime,
        TransactionType type,
        Channel channel,
        BigDecimal amount,
        String currency,
        String cardToken,
        String merchantId,
        String mcc,
        String merchantCountry,
        String beneficiaryId,
        String beneficiaryCountry,
        String deviceId,
        String ipAddress,
        String ipCountry) {

    public boolean isTransfer() {
        return type == TransactionType.TRANSFER;
    }
}
