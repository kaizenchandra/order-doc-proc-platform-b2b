package com.synechisveltiosi.platform.order.adapter.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "api_idempotency")
public class IdempotencyRecord {
    @EmbeddedId private IdempotencyId id;
    @Column(nullable = false, length = 64) private String requestHash;
    @Column(nullable = false) private UUID resourceId;
    @Column(nullable = false) private int responseStatus;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String responseBody;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant expiresAt;
    protected IdempotencyRecord() {}
    public IdempotencyRecord(IdempotencyId id, String hash, UUID resourceId, int status,
            String responseBody, Instant createdAt, Instant expiresAt) {
        this.id = Objects.requireNonNull(id);
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid request hash");
        if (status < 200 || status > 299) throw new IllegalArgumentException("Expected successful response");
        this.requestHash = hash;
        this.resourceId = Objects.requireNonNull(resourceId);
        this.responseStatus = status;
        this.responseBody = Objects.requireNonNull(responseBody);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("Invalid expiry");
    }
    public IdempotencyId id() { return id; }
    public String requestHash() { return requestHash; }
    public UUID resourceId() { return resourceId; }
    public int responseStatus() { return responseStatus; }
    public String responseBody() { return responseBody; }
    public Instant expiresAt() { return expiresAt; }
}
