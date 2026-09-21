package com.fraudplatform.fileadapter.spec;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File naming contract: {@code <TYPE>_<tenant>_<yyyyMMdd>_<seq>.<csv|jsonl>}, e.g.
 * {@code TXN_HISTORY_aldermoor-bank_20260430_001.csv}. A companion {@code <name>.done} marker signals
 * that the sender finished writing (never read a file that is still being transferred).
 */
public record FileName(FileSpec spec, String tenantId, LocalDate businessDate, int sequence, String fileName) {

    private static final Pattern PATTERN = Pattern.compile(
            "^(TXN_HISTORY|CHARGEBACKS|FRAUD_LABELS|CUSTOMER_PROFILES|SETTLEMENT)_([a-z0-9-]+)_(\\d{8})_(\\d{3})\\.(csv|jsonl)$");

    public static Optional<FileName> parse(String name) {
        Matcher m = PATTERN.matcher(name);
        if (!m.matches()) return Optional.empty();
        FileSpec spec = FileSpec.valueOf(m.group(1));
        if (!spec.extension().equals(m.group(5))) return Optional.empty();
        try {
            return Optional.of(new FileName(spec, m.group(2), LocalDate.parse(m.group(3), DateTimeFormatter.BASIC_ISO_DATE),
                    Integer.parseInt(m.group(4)), name));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
