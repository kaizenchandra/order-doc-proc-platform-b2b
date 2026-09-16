package com.synechisveltiosi.platform.order.adapter.persistence;

import com.synechisveltiosi.platform.order.domain.OrderDocument;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface OrderDocumentRepository extends Repository<OrderDocument, UUID> {
    OrderDocument save(OrderDocument document);

    Optional<OrderDocument> findByTenantIdAndOrderIdAndId(UUID tenantId, UUID orderId, UUID id);

    Slice<OrderDocument> findByTenantIdAndOrderId(UUID tenantId, UUID orderId, Pageable pageable);
}
