package com.synechisveltiosi.platform.notification.adapter.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "inbox_events")
public class InboxEvent {
    @EmbeddedId private InboxId id;
    @Column(nullable = false) private Instant processedAt;
    protected InboxEvent() {}
    public InboxId id() { return id; }
    public Instant processedAt() { return processedAt; }
}
