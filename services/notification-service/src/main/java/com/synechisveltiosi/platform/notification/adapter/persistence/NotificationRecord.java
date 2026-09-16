package com.synechisveltiosi.platform.notification.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "notification_records")
public class NotificationRecord {
    @Id
    private UUID id;
    @Column(nullable = false)
    private UUID auditRecordId;
    @Column(nullable = false, length = 24)
    private String channel;
    @Column(nullable = false, length = 24)
    private String status;
    @Column(nullable = false)
    private Instant createdAt;

    protected NotificationRecord() {
    }

    public NotificationRecord(UUID id, UUID auditRecordId, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.auditRecordId = Objects.requireNonNull(auditRecordId);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.channel = "IN_APP";
        this.status = "RECORDED";
    }

    public UUID id() {
        return id;
    }

    public String status() {
        return status;
    }
}
