package com.synechisveltiosi.platform.order.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "inbox_events")
public class InboxEvent {
    @EmbeddedId
    private InboxId id;
    @Column(nullable = false)
    private Instant processedAt;

    protected InboxEvent() {
    }

    public InboxId id() {
        return id;
    }

    public Instant processedAt() {
        return processedAt;
    }
}
