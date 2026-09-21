package com.fraudplatform.fileadapter.spec;

import com.fraudplatform.fileadapter.spec.FieldSpec.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.fraudplatform.fileadapter.spec.FieldSpec.oneOf;
import static com.fraudplatform.fileadapter.spec.FieldSpec.opt;
import static com.fraudplatform.fileadapter.spec.FieldSpec.req;

/**
 * File specifications agreed with the customer (see docs/FILE_INTEGRATIONS.md). CSV headers must match
 * exactly, in order: a different header means a different schema version, and the whole file is rejected
 * rather than guessed at.
 */
public enum FileSpec {

    TXN_HISTORY(Format.CSV, "core-banking", List.of(
            req("transaction_id", Type.TEXT), req("event_time", Type.TIMESTAMP), req("customer_id", Type.TEXT),
            req("account_id", Type.TEXT), oneOf("transaction_type", true, "CARD_PAYMENT", "TRANSFER"),
            oneOf("channel", true, "POS", "ECOM", "MOBILE", "WEB", "OPEN_BANKING", "BRANCH"),
            req("amount", Type.DECIMAL), req("currency", Type.CURRENCY), opt("card_token", Type.TEXT),
            opt("merchant_id", Type.TEXT), opt("mcc", Type.MCC), opt("merchant_country", Type.COUNTRY),
            opt("beneficiary_id", Type.TEXT), opt("beneficiary_country", Type.COUNTRY), opt("device_id", Type.TEXT),
            opt("ip_address", Type.TEXT), opt("ip_country", Type.COUNTRY)),
            r -> str(r, "transaction_id"), 0.05),

    CHARGEBACKS(Format.CSV, "card-processor", List.of(
            req("transaction_id", Type.TEXT), req("chargeback_date", Type.DATE), req("reason_code", Type.TEXT),
            req("amount", Type.DECIMAL), req("currency", Type.CURRENCY), req("customer_id", Type.TEXT)),
            r -> str(r, "transaction_id"), 0.05),

    FRAUD_LABELS(Format.JSONL, "fraud-operations", List.of(
            req("transactionId", Type.TEXT), req("customerId", Type.TEXT), oneOf("label", true, "FRAUD", "GENUINE"),
            opt("fraudType", Type.TEXT), req("reportedAt", Type.TIMESTAMP), oneOf("source", true, "CUSTOMER_REPORT", "BATCH_LABEL")),
            r -> str(r, "transactionId") + "|" + str(r, "source"), 0.05),

    CUSTOMER_PROFILES(Format.JSONL, "customer-master", List.of(
            req("customerId", Type.TEXT), req("segment", Type.TEXT), req("homeCountry", Type.COUNTRY),
            req("tenureDays", Type.INTEGER), req("avgAmount90d", Type.DECIMAL), oneOf("riskTier", true, "low", "standard", "elevated"),
            opt("boundDeviceIds", Type.TEXT_LIST)),
            r -> str(r, "customerId"), 0.02),

    SETTLEMENT(Format.CSV, "core-banking", List.of(
            req("transaction_id", Type.TEXT), req("settlement_date", Type.DATE), req("settled_amount", Type.DECIMAL),
            req("currency", Type.CURRENCY), oneOf("status", true, "SETTLED", "REVERSED")),
            r -> str(r, "transaction_id"), 0.01);

    public enum Format { CSV, JSONL }

    private final Format format;
    private final String sourceSystem;
    private final List<FieldSpec> fields;
    private final Function<Map<String, Object>, String> naturalKey;
    private final double maxInvalidRatio;

    FileSpec(Format format, String sourceSystem, List<FieldSpec> fields, Function<Map<String, Object>, String> naturalKey,
             double maxInvalidRatio) {
        this.format = format;
        this.sourceSystem = sourceSystem;
        this.fields = fields;
        this.naturalKey = naturalKey;
        this.maxInvalidRatio = maxInvalidRatio;
    }

    public Format format() {
        return format;
    }

    public String sourceSystem() {
        return sourceSystem;
    }

    public List<FieldSpec> fields() {
        return fields;
    }

    public List<String> header() {
        return fields.stream().map(FieldSpec::name).toList();
    }

    public String naturalKey(Map<String, Object> record) {
        return naturalKey.apply(record);
    }

    /** Above this share of invalid records the file is rejected as a systemic problem, not partially processed. */
    public double maxInvalidRatio() {
        return maxInvalidRatio;
    }

    public String extension() {
        return format == Format.CSV ? "csv" : "jsonl";
    }

    public List<String> validate(Map<String, Object> record) {
        List<String> errors = new ArrayList<>();
        for (FieldSpec f : fields) {
            String e = f.validate(record.get(f.name()));
            if (e != null) errors.add(e);
        }
        if (this == TXN_HISTORY) {
            Object type = record.get("transaction_type");
            if ("CARD_PAYMENT".equals(type) && (blank(record, "card_token") || blank(record, "merchant_id"))) {
                errors.add("card payments require card_token and merchant_id");
            }
            if ("TRANSFER".equals(type) && blank(record, "beneficiary_id")) errors.add("transfers require beneficiary_id");
            Object token = record.get("card_token");
            if (token != null && token.toString().matches("\\d{13,19}")) errors.add("card_token looks like a raw PAN");
        }
        return errors;
    }

    private static boolean blank(Map<String, Object> r, String f) {
        Object v = r.get(f);
        return v == null || v.toString().isBlank();
    }

    static String str(Map<String, Object> r, String field) {
        Object v = r.get(field);
        return v == null ? "" : v.toString().trim();
    }
}
