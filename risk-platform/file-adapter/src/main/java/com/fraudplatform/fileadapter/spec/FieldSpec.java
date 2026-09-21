package com.fraudplatform.fileadapter.spec;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** One field of a file record: type, whether it is required, and constraints. */
public record FieldSpec(String name, Type type, boolean required, Set<String> allowed, int maxLength) {

    public enum Type { TEXT, DECIMAL, INTEGER, DATE, TIMESTAMP, COUNTRY, CURRENCY, MCC, ENUM, TEXT_LIST }

    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern MCC = Pattern.compile("\\d{4}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]+");

    public static FieldSpec req(String name, Type type) {
        return new FieldSpec(name, type, true, Set.of(), 64);
    }

    public static FieldSpec opt(String name, Type type) {
        return new FieldSpec(name, type, false, Set.of(), 64);
    }

    public static FieldSpec oneOf(String name, boolean required, String... values) {
        return new FieldSpec(name, Type.ENUM, required, Set.of(values), 32);
    }

    /** @return an error message, or null if the value is valid. */
    public String validate(Object raw) {
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return required ? name + ": required" : null;
        }
        if (type == Type.TEXT_LIST) {
            return raw instanceof List<?> ? null : name + ": must be a list";
        }
        String v = raw.toString().trim();
        try {
            return switch (type) {
                case TEXT -> v.length() > maxLength ? name + ": longer than " + maxLength
                        : !ID.matcher(v).matches() ? name + ": invalid characters" : null;
                case DECIMAL -> {
                    BigDecimal d = new BigDecimal(v);
                    yield d.signum() <= 0 ? name + ": must be positive" : d.scale() > 2 ? name + ": max 2 decimals" : null;
                }
                case INTEGER -> Long.parseLong(v) < 0 ? name + ": must be >= 0" : null;
                case DATE -> {
                    LocalDate.parse(v);
                    yield null;
                }
                case TIMESTAMP -> {
                    Instant.parse(v);
                    yield null;
                }
                case COUNTRY -> COUNTRY.matcher(v).matches() ? null : name + ": ISO country code expected";
                case CURRENCY -> CURRENCY.matcher(v).matches() ? null : name + ": ISO currency code expected";
                case MCC -> MCC.matcher(v).matches() ? null : name + ": 4-digit MCC expected";
                case ENUM -> allowed.contains(v) ? null : name + ": must be one of " + allowed;
                case TEXT_LIST -> null;
            };
        } catch (RuntimeException e) {
            return name + ": invalid " + type.name().toLowerCase() + " '" + (v.length() > 40 ? v.substring(0, 40) : v) + "'";
        }
    }
}
