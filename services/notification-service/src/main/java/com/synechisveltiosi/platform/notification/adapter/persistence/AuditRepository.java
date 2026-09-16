package com.synechisveltiosi.platform.notification.adapter.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.Repository;

import java.util.UUID;

public interface AuditRepository extends Repository<AuditRecord, UUID> {
    Slice<AuditRecord> findByTenantIdAndAggregateId(UUID tenantId, UUID aggregateId, Pageable pageable);
}
