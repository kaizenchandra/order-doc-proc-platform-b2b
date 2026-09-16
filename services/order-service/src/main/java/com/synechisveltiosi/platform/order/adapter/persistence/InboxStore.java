package com.synechisveltiosi.platform.order.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Repository
public class InboxStore {
    private final JdbcTemplate jdbc;

    public InboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Mandatory: an inbox claim must never commit before its business effect.
    // ON CONFLICT resolves concurrent duplicate delivery without aborting PostgreSQL's transaction.
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(String consumerName, UUID eventId) {
        Objects.requireNonNull(eventId);
        if (consumerName == null || consumerName.isBlank() || consumerName.length() > 100)
            throw new IllegalArgumentException("Invalid consumer name");
        return jdbc.update("""
                INSERT INTO inbox_events (consumer_name, event_id)
                VALUES (?, ?) ON CONFLICT (consumer_name, event_id) DO NOTHING
                """, consumerName, eventId) == 1;
    }
}
