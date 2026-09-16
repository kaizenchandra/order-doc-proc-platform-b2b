package com.synechisveltiosi.platform.order.adapter.persistence;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class IdempotencyId implements Serializable {
    @Column(name = "tenant_id") private UUID tenantId;
    @Column(length = 200) private String operation;
    @Column(name = "idempotency_key", length = 128) private String idempotencyKey;
    protected IdempotencyId() {}
    public IdempotencyId(UUID tenantId, String operation, String key) {
        this.tenantId = Objects.requireNonNull(tenantId);
        this.operation = Objects.requireNonNull(operation);
        this.idempotencyKey = Objects.requireNonNull(key);
    }
    public UUID tenantId() { return tenantId; }
    public String operation() { return operation; }
    public String idempotencyKey() { return idempotencyKey; }
    @Override public boolean equals(Object other) {
        return other instanceof IdempotencyId that && Objects.equals(tenantId, that.tenantId)
            && Objects.equals(operation, that.operation) && Objects.equals(idempotencyKey, that.idempotencyKey);
    }
    @Override public int hashCode() { return Objects.hash(tenantId, operation, idempotencyKey); }
}
