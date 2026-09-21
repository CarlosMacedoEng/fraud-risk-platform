package com.fraudplatform.fileadapter.ingest;

import com.fraudplatform.fileadapter.spec.FileSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Streams records from CSV (RFC 4180 quoting) or JSON Lines, keeping line numbers for quarantine reports. */
public final class RecordReader {

    public record Line(int number, String raw, Map<String, Object> values, String parseError) {
    }

    public static final class SchemaMismatchException extends RuntimeException {
        public final List<String> expected;
        public final List<String> actual;

        SchemaMismatchException(List<String> expected, List<String> actual) {
            super("header mismatch: expected " + expected + " but found " + actual);
            this.expected = expected;
            this.actual = actual;
        }
    }

    private RecordReader() {
    }

    public static List<Line> read(BufferedReader in, FileSpec spec, ObjectMapper json) throws IOException {
        return spec.format() == FileSpec.Format.CSV ? csv(in, spec) : jsonl(in, json);
    }

    private static List<Line> csv(BufferedReader in, FileSpec spec) throws IOException {
        String header = in.readLine();
        if (header != null && header.startsWith("﻿")) header = header.substring(1);   // UTF-8 BOM from Excel exports
        List<String> actual = header == null ? List.of() : parse(header);
        if (!actual.equals(spec.header())) throw new SchemaMismatchException(spec.header(), actual);
        List<Line> out = new ArrayList<>();
        String raw;
        int n = 1;
        while ((raw = in.readLine()) != null) {
            n++;
            if (raw.isBlank()) continue;
            List<String> cells;
            try {
                cells = parse(raw);
            } catch (IllegalArgumentException e) {
                out.add(new Line(n, raw, Map.of(), e.getMessage()));
                continue;
            }
            if (cells.size() != actual.size()) {
                out.add(new Line(n, raw, Map.of(), "expected " + actual.size() + " columns, found " + cells.size()));
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < cells.size(); i++) values.put(actual.get(i), cells.get(i).isEmpty() ? null : cells.get(i));
            out.add(new Line(n, raw, values, null));
        }
        return out;
    }

    /** Minimal RFC 4180 line parser: commas, double-quoted fields, doubled quotes as escapes. */
    static List<String> parse(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (quoted) throw new IllegalArgumentException("unterminated quoted field");
        out.add(cur.toString());
        return out;
    }

    private static List<Line> jsonl(BufferedReader in, ObjectMapper json) throws IOException {
        List<Line> out = new ArrayList<>();
        String raw;
        int n = 0;
        while ((raw = in.readLine()) != null) {
            n++;
            if (raw.isBlank()) continue;
            try {
                JsonNode node = json.readTree(raw);
                if (!node.isObject()) {
                    out.add(new Line(n, raw, Map.of(), "line is not a JSON object"));
                    continue;
                }
                Map<String, Object> values = new LinkedHashMap<>();
                node.properties().forEach(e -> {
                    JsonNode v = e.getValue();
                    if (v.isArray()) {
                        List<String> list = new ArrayList<>();
                        v.forEach(x -> list.add(x.asString()));
                        values.put(e.getKey(), list);
                    } else {
                        values.put(e.getKey(), v.isNull() ? null : v.asString());
                    }
                });
                out.add(new Line(n, raw, values, null));
            } catch (RuntimeException e) {
                out.add(new Line(n, raw, Map.of(), "invalid JSON"));
            }
        }
        return out;
    }
}
