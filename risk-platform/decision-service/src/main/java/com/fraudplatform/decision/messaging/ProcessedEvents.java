package com.fraudplatform.decision.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Consumer-side de-duplication (at-least-once delivery ⇒ duplicates are normal, not exceptional). */
@Component
public class ProcessedEvents {

    private final JdbcClient jdbc;

    public ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean alreadyProcessed(String group, UUID eventId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM processed_events WHERE consumer_group = ? AND event_id = ?)")
                .params(group, eventId).query(Boolean.class).single());
    }

    /** @return false if another delivery already marked it (lost the race). */
    public boolean markProcessed(String group, UUID eventId) {
        return jdbc.sql("INSERT INTO processed_events (consumer_group, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(group, eventId).update() == 1;
    }
}
