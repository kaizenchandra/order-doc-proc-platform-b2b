package com.synechisveltiosi.platform.notification.application;

import com.synechisveltiosi.platform.eventcontracts.Events;
import com.synechisveltiosi.platform.notification.adapter.persistence.AuditJournal;
import com.synechisveltiosi.platform.notification.adapter.persistence.AuditRecord;
import com.synechisveltiosi.platform.notification.adapter.persistence.InboxStore;
import com.synechisveltiosi.platform.notification.adapter.persistence.NotificationRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationHandler {
    public static final String CONSUMER = "notification-domain";
    private final InboxStore inbox;
    private final AuditJournal journal;
    private final JsonMapper json = JsonMapper.builder().build();

    public NotificationHandler(InboxStore inbox, AuditJournal journal) {
        this.inbox = inbox;
        this.journal = journal;
    }

    @Transactional
    public void handle(Events.Envelope event) {
        // Only deliberately selected metadata is retained; no customer details or storage locations.
        Map<String, String> summary = switch (event.data()) {
            case Events.OrderCreated order -> Map.of("status", order.status());
            case Events.OrderStatusChanged order ->
                    Map.of("previousStatus", order.previousStatus(), "status", order.status());
            case Events.DocumentResult result -> Map.of("documentId", result.documentId().toString(),
                    "processingRequestId", result.processingRequestId().toString(),
                    "outcome", result.failureCode() == null ? "PROCESSED" : "FAILED",
                    "processorVersion", result.processorVersion());
            default -> throw new IllegalArgumentException("Unsupported notification event");
        };
        if (!inbox.claim(CONSUMER, event.eventId())) return;
        UUID auditId = UUID.randomUUID();
        Instant now = Instant.now();
        journal.append(new AuditRecord(auditId, CONSUMER, event.eventId(), event.tenantId(), event.aggregateId(),
                event.eventType(), event.eventVersion(), event.correlationId(), event.occurredAt(), now,
                json.writeValueAsString(summary)), new NotificationRecord(UUID.randomUUID(), auditId, now));
    }
}
