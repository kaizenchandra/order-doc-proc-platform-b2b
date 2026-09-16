package com.synechisveltiosi.platform.order.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id
    private UUID eventId;
    @Column(nullable = false, updatable = false)
    private UUID tenantId;
    @Column(nullable = false, updatable = false)
    private UUID aggregateId;
    @Column(nullable = false, updatable = false, length = 100)
    private String eventType;
    @Column(nullable = false, updatable = false)
    private int eventVersion;
    @Column(nullable = false, updatable = false)
    private UUID correlationId;
    @Column(updatable = false)
    private UUID causationId;
    @Column(nullable = false, updatable = false)
    private Instant occurredAt;
    @Column(nullable = false, updatable = false, length = 100)
    private String source;
    @Column(nullable = false, updatable = false, length = 100)
    private String destination;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private String data;
    @Column(updatable = false, length = 128)
    private String traceparent;
    @Column(nullable = false)
    private Instant availableAt;
    @Column(nullable = false)
    private int attemptCount;
    private UUID claimToken;
    private Instant leaseUntil;
    private Instant publishedAt;
    @Column(length = 100)
    private String lastErrorCode;

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID eventId, UUID tenantId, UUID aggregateId, String eventType, int eventVersion,
                       UUID correlationId, UUID causationId, Instant occurredAt, String source,
                       String destination, String data, String traceparent) {
        this.eventId = Objects.requireNonNull(eventId);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.aggregateId = Objects.requireNonNull(aggregateId);
        this.eventType = Objects.requireNonNull(eventType);
        if (eventVersion <= 0) throw new IllegalArgumentException("Invalid event version");
        this.eventVersion = eventVersion;
        this.correlationId = Objects.requireNonNull(correlationId);
        this.causationId = causationId;
        this.occurredAt = Objects.requireNonNull(occurredAt);
        this.source = Objects.requireNonNull(source);
        this.destination = Objects.requireNonNull(destination);
        this.data = Objects.requireNonNull(data);
        this.traceparent = traceparent;
        this.availableAt = occurredAt;
    }

    public UUID eventId() {
        return eventId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID aggregateId() {
        return aggregateId;
    }

    public String eventType() {
        return eventType;
    }

    public int eventVersion() {
        return eventVersion;
    }

    public UUID correlationId() {
        return correlationId;
    }

    public UUID causationId() {
        return causationId;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public String source() {
        return source;
    }

    public String destination() {
        return destination;
    }

    public String data() {
        return data;
    }

    public String traceparent() {
        return traceparent;
    }

    public Instant availableAt() {
        return availableAt;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public UUID claimToken() {
        return claimToken;
    }

    public Instant leaseUntil() {
        return leaseUntil;
    }

    public Instant publishedAt() {
        return publishedAt;
    }
}
