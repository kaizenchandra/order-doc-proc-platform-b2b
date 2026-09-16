package com.synechisveltiosi.platform.order.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID tenantId;
    @Column(nullable = false, updatable = false) private UUID customerId;
    @Column(nullable = false, length = 100, updatable = false) private String customerReference;
    @Column(nullable = false, precision = 19, scale = 2, updatable = false) private BigDecimal totalAmount;
    @Column(nullable = false, length = 3, updatable = false) private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private OrderStatus status;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    // A nullable wrapper lets Spring Data identify new entities with assigned UUIDs.
    @Version private Long version;

    protected Order() {}

    public static Order create(UUID id, UUID tenantId, UUID customerId, String reference,
                               BigDecimal amount, String currency, Instant now) {
        Objects.requireNonNull(amount, "amount");
        BigDecimal normalized = amount.setScale(2, RoundingMode.UNNECESSARY);
        if (normalized.signum() <= 0 || normalized.precision() > 19)
            throw new IllegalArgumentException("Amount must be positive and fit numeric(19,2)");
        if (!Set.of("USD", "EUR", "GBP", "INR").contains(Objects.requireNonNull(currency)))
            throw new IllegalArgumentException("Unsupported currency");
        var order = new Order();
        order.id = Objects.requireNonNull(id);
        order.tenantId = Objects.requireNonNull(tenantId);
        order.customerId = Objects.requireNonNull(customerId);
        order.customerReference = DomainChecks.text(reference, 100, "customerReference");
        order.totalAmount = normalized;
        order.currency = currency;
        order.status = OrderStatus.CREATED;
        order.createdAt = Objects.requireNonNull(now);
        order.updatedAt = now;
        return order;
    }

    public boolean transitionTo(OrderStatus target, Instant now) {
        Objects.requireNonNull(target);
        if (status == target) return false;
        if (!status.allows(target)) throw new IllegalStateException("Invalid order transition: " + status + " -> " + target);
        DomainChecks.time(now, updatedAt);
        status = target;
        updatedAt = now;
        return true;
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID customerId() { return customerId; }
    public String customerReference() { return customerReference; }
    public BigDecimal totalAmount() { return totalAmount; }
    public String currency() { return currency; }
    public OrderStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Long version() { return version; }
}
