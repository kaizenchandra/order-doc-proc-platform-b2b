package com.synechisveltiosi.platform.notification.adapter.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "audit_records")
public class AuditRecord {
    @Id private UUID id;
    @Column(nullable = false, length = 100) private String consumerName;
    @Column(nullable = false) private UUID eventId;
    @Column(nullable = false) private UUID tenantId;
    @Column(nullable = false) private UUID aggregateId;
    @Column(nullable = false, length = 100) private String eventType;
    @Column(nullable = false) private int eventVersion;
    @Column(nullable = false) private UUID correlationId;
    @Column(nullable = false) private Instant occurredAt;
    @Column(nullable = false) private Instant recordedAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String summary;
    protected AuditRecord() {}
    public AuditRecord(UUID id, String consumerName, UUID eventId, UUID tenantId, UUID aggregateId,
            String eventType, int eventVersion, UUID correlationId, Instant occurredAt, Instant recordedAt, String summary) {
        this.id = Objects.requireNonNull(id);
        this.consumerName = Objects.requireNonNull(consumerName);
        this.eventId = Objects.requireNonNull(eventId);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.aggregateId = Objects.requireNonNull(aggregateId);
        this.eventType = Objects.requireNonNull(eventType);
        if (eventVersion <= 0) throw new IllegalArgumentException("Invalid event version");
        this.eventVersion = eventVersion;
        this.correlationId = Objects.requireNonNull(correlationId);
        this.occurredAt = Objects.requireNonNull(occurredAt);
        this.recordedAt = Objects.requireNonNull(recordedAt);
        this.summary = Objects.requireNonNull(summary);
    }
    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID eventId() { return eventId; }
    public String summary() { return summary; }
}
