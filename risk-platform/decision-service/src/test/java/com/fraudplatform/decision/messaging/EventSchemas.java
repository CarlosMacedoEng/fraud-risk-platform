package com.fraudplatform.decision.messaging;

import com.fraudplatform.decision.TestPaths;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Validates events against the published JSON Schemas in docs/events (draft 2020-12). */
final class EventSchemas {

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Map<String, Schema> CACHE = new ConcurrentHashMap<>();

    private EventSchemas() {
    }

    private static Schema schema(String file) {
        return CACHE.computeIfAbsent(file, f -> {
            try {
                return REGISTRY.getSchema(Files.readString(TestPaths.REPO.resolve("docs/events").resolve(f)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** @return validation errors for envelope + payload (empty = valid). */
    static List<String> validate(JsonNode envelope) {
        List<String> errors = new java.util.ArrayList<>();
        schema("envelope.v1.schema.json").validate(envelope).forEach(e -> errors.add("envelope: " + e.getMessage()));
        String type = envelope.path("eventType").asString();
        schema(type + ".v" + envelope.path("eventVersion").asInt() + ".schema.json").validate(envelope.get("payload"))
                .forEach(e -> errors.add(type + ": " + e.getMessage()));
        return errors;
    }
}
