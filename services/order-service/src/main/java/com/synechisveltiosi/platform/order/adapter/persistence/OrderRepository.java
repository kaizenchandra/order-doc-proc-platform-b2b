package com.synechisveltiosi.platform.order.adapter.persistence;

import com.synechisveltiosi.platform.order.domain.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends Repository<Order, UUID> {
    Order save(Order order);

    Optional<Order> findByTenantIdAndId(UUID tenantId, UUID id);

    // Used when an operation must serialize against other changes to the same order.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.tenantId = :tenantId and o.id = :id")
    Optional<Order> lockByTenantIdAndId(UUID tenantId, UUID id);
}
