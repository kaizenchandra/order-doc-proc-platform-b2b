package com.synechisveltiosi.platform.order.adapter.persistence;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public class OrderJournal {
    private final EntityManager entityManager;

    public OrderJournal(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    // persist (not merge) prevents a reused event ID from rewriting a committed event.
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OutboxEvent event) {
        entityManager.persist(event);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void remember(IdempotencyRecord record) {
        entityManager.persist(record);
    }

    // Flush behind the repository proxy to translate provider errors and expose updated JPA versions.
    @Transactional(propagation = Propagation.MANDATORY)
    public void flush() {
        entityManager.flush();
    }

    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> find(IdempotencyId id) {
        return Optional.ofNullable(entityManager.find(IdempotencyRecord.class, id));
    }
}
