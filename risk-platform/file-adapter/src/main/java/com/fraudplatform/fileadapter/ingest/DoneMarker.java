package com.fraudplatform.fileadapter.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Completeness marker written by the sender after the data file: {@code <file>.done} with
 * {@code records=<n>} and {@code sha256=<hex>}. Protects against processing partially transferred files
 * and detects truncation/corruption in transit.
 */
public record DoneMarker(long records, String sha256) {

    public static Path pathFor(Path dataFile) {
        return dataFile.resolveSibling(dataFile.getFileName() + ".done");
    }

    public static Optional<DoneMarker> read(Path dataFile) throws IOException {
        Path marker = pathFor(dataFile);
        if (!Files.exists(marker)) return Optional.empty();
        Map<String, String> kv = new HashMap<>();
        for (String line : Files.readAllLines(marker)) {
            int eq = line.indexOf('=');
            if (eq > 0) kv.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        try {
            return Optional.of(new DoneMarker(Long.parseLong(kv.getOrDefault("records", "-1")), kv.get("sha256")));
        } catch (NumberFormatException e) {
            return Optional.of(new DoneMarker(-1, kv.get("sha256")));
        }
    }
}
