package com.synechisveltiosi.platform.notification.adapter.persistence;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AuditJournal {
    private final EntityManager entityManager;
    public AuditJournal(EntityManager entityManager) { this.entityManager = entityManager; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(AuditRecord audit, NotificationRecord notification) {
        entityManager.persist(audit);
        entityManager.persist(notification);
    }
}
