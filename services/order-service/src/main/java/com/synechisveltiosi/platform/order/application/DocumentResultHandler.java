package com.synechisveltiosi.platform.order.application;

import com.synechisveltiosi.platform.eventcontracts.Events;
import com.synechisveltiosi.platform.order.adapter.persistence.InboxStore;
import com.synechisveltiosi.platform.order.adapter.persistence.OrderDocumentRepository;
import com.synechisveltiosi.platform.order.adapter.persistence.OrderJournal;
import com.synechisveltiosi.platform.order.adapter.persistence.OrderRepository;
import com.synechisveltiosi.platform.order.config.MessagingProperties;
import com.synechisveltiosi.platform.order.domain.GcsObjectReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class DocumentResultHandler {
    private final InboxStore inbox;
    private final OrderRepository orders;
    private final OrderDocumentRepository documents;
    private final OrderJournal journal;
    private final MessagingProperties settings;
    private final Clock clock;

    public DocumentResultHandler(InboxStore inbox, OrderRepository orders, OrderDocumentRepository documents,
                                 OrderJournal journal, MessagingProperties settings, Clock clock) {
        this.inbox = inbox;
        this.orders = orders;
        this.documents = documents;
        this.journal = journal;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(timeout = 10)
    public Outcome handle(Events.Envelope event) {
        if (!(event.data() instanceof Events.DocumentResult result) || !"document-service".equals(event.source()))
            throw new IllegalArgumentException("Expected document result");
        if (settings.reportBucket().isBlank() || !settings.reportBucket().equals(result.reportBucket())
                || !Events.reportObject(event.tenantId(), result.processingRequestId()).equals(result.reportObjectName()))
            throw new IllegalArgumentException("Report location is not allowed");
        if (!inbox.claim("order-results", event.eventId())) return Outcome.DUPLICATE;
        orders.lockByTenantIdAndId(event.tenantId(), event.aggregateId()).orElseThrow(ApiFailure::notFound);
        var document = documents.findByTenantIdAndOrderIdAndId(event.tenantId(), event.aggregateId(), result.documentId())
                .orElseThrow(ApiFailure::notFound);
        if (!result.processingRequestId().equals(document.processingRequestId())) return Outcome.STALE;
        if (!result.processorVersion().equals(document.processorVersion()))
            throw new IllegalArgumentException("Processor version mismatch");
        var report = new GcsObjectReference(result.reportBucket(), result.reportObjectName(), Long.parseLong(result.reportGeneration()));
        var now = clock.instant();
        if (now.isBefore(document.updatedAt())) now = document.updatedAt();
        boolean applied = result.sha256() != null
                ? document.succeed(result.processingRequestId(), report, result.sha256(), now)
                : document.fail(result.processingRequestId(), report, result.failureCode(), now);
        journal.flush();
        return applied ? Outcome.APPLIED : Outcome.STALE;
    }

    public enum Outcome {APPLIED, DUPLICATE, STALE}
}
