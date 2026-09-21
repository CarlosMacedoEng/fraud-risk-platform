package com.fraudplatform.decision.api.dto;

import com.fraudplatform.decision.domain.Channel;
import com.fraudplatform.decision.domain.TransactionType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * v1 scoring request. Additive, optional fields may be introduced within v1; removing or changing the
 * meaning of a field requires v2 (see API_INTEGRATIONS.md).
 */
public record ScoreRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String transactionId,
        @NotBlank @Size(max = 64) String customerId,
        @NotBlank @Size(max = 64) String accountId,
        @NotNull Instant eventTime,
        @NotNull TransactionType transactionType,
        @NotNull Channel channel,
        @NotNull @DecimalMin(value = "0.01") @DecimalMax(value = "1000000.00") @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @Size(max = 64) String cardToken,
        @Size(max = 64) String merchantId,
        @Pattern(regexp = "\\d{4}") String mcc,
        @Pattern(regexp = "[A-Z]{2}") String merchantCountry,
        @Size(max = 64) String beneficiaryId,
        @Pattern(regexp = "[A-Z]{2}") String beneficiaryCountry,
        @Size(max = 128) String deviceId,
        @Size(max = 45) String ipAddress,
        @Pattern(regexp = "[A-Z]{2}") String ipCountry) {

    @AssertTrue(message = "card payments require cardToken and merchantId")
    public boolean isCardPaymentComplete() {
        return transactionType != TransactionType.CARD_PAYMENT || (cardToken != null && merchantId != null);
    }

    @AssertTrue(message = "transfers require beneficiaryId")
    public boolean isTransferComplete() {
        return transactionType != TransactionType.TRANSFER || beneficiaryId != null;
    }

    @AssertTrue(message = "raw card numbers are not accepted; send a token")
    public boolean isNotRawPan() {
        return cardToken == null || !cardToken.matches("\\d{13,19}");
    }
}
