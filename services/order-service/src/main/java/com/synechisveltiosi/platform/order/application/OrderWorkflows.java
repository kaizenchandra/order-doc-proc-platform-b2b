package com.synechisveltiosi.platform.order.application;

import com.synechisveltiosi.platform.order.adapter.persistence.*;
import com.synechisveltiosi.platform.order.domain.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import com.synechisveltiosi.platform.eventcontracts.Events;
import java.util.UUID;
import static com.synechisveltiosi.platform.order.application.OrderModels.*;

@Service
@Transactional(timeout = 10)
public class OrderWorkflows {
    private final OrderRepository orders;
    private final OrderDocumentRepository documents;
    private final OrderJournal journal;
    private final IdempotentRequests idempotency;
    private final ObjectMapper json;
    private final Clock clock;
    private final String uploadBucket;
    public OrderWorkflows(OrderRepository orders, OrderDocumentRepository documents, OrderJournal journal,
            IdempotentRequests idempotency, ObjectMapper json, Clock clock,
            @Value("${app.storage.upload-bucket:}") String uploadBucket) {
        this.orders = orders; this.documents = documents; this.journal = journal; this.idempotency = idempotency;
        this.json = json; this.clock = clock; this.uploadBucket = uploadBucket;
    }
    public StoredResponse create(UUID tenant, String key, CreateOrder input, UUID correlation) {
        var normalized = new CreateOrder(input.customerId(), input.customerReference(), input.totalAmount().setScale(2), input.currency());
        return idempotency.execute(tenant, "POST:/api/v1/orders", key, normalized, () -> {
            var order = Order.create(UUID.randomUUID(), tenant, input.customerId(), input.customerReference(),
                    input.totalAmount(), input.currency(), clock.instant());
            orders.save(order);
            append(tenant, order.id(), "OrderCreated", "order-events", correlation,
                    new Events.OrderCreated(order.id(), order.customerId(), order.status().name(), order.totalAmount(), order.currency()));
            journal.flush();
            return new StoredResponse(order.id(), json.writeValueAsString(OrderView.of(order)));
        });
    }
    @Transactional(readOnly = true)
    public OrderView get(UUID tenant, UUID orderId) {
        return OrderView.of(orders.findByTenantIdAndId(tenant, orderId).orElseThrow(ApiFailure::notFound));
    }
    public OrderView changeStatus(UUID tenant, UUID orderId, ChangeStatus input, UUID correlation) {
        var order = lockOrder(tenant, orderId);
        if (!order.version().equals(input.expectedVersion())) throw ApiFailure.conflict("Order version has changed; read the order again");
        var previous = order.status();
        try {
            if (order.transitionTo(input.status(), clock.instant())) {
                append(tenant, orderId, "OrderStatusChanged", "order-events", correlation,
                        new Events.OrderStatusChanged(orderId, previous.name(), order.status().name()));
            }
        } catch (IllegalStateException invalidTransition) {
            throw ApiFailure.conflict("The requested order status transition is not allowed");
        }
        journal.flush();
        return OrderView.of(order);
    }
    public UUID register(UUID tenant, UUID orderId, String key, RegisterDocument input) {
        return idempotency.execute(tenant, "POST:/api/v1/orders/" + orderId + "/documents", key, input, () -> {
            requireOpen(lockOrder(tenant, orderId));
            if (uploadBucket.isBlank()) throw new ApiFailure(503, "Document storage is not configured");
            UUID documentId = UUID.randomUUID();
            var now = clock.instant();
            var document = OrderDocument.register(documentId, tenant, orderId, input.fileName(), input.contentType(),
                    uploadBucket, tenant + "/" + orderId + "/" + documentId, now.plusSeconds(600), now);
            documents.save(document);
            journal.flush();
            return new StoredResponse(documentId, json.writeValueAsString(DocumentView.of(document)));
        }).resourceId();
    }
    @Transactional(readOnly = true)
    public OrderDocument document(UUID tenant, UUID orderId, UUID documentId) {
        return documents.findByTenantIdAndOrderIdAndId(tenant, orderId, documentId).orElseThrow(ApiFailure::notFound);
    }
    public DocumentView complete(UUID tenant, UUID orderId, UUID documentId, VerifiedUpload upload, UUID correlation) {
        var order = lockOrder(tenant, orderId);
        var document = document(tenant, orderId, documentId);
        if (hasProcessingAttempt(document)) return DocumentView.of(document);
        requireOpen(order);
        try { document.queue(upload, UUID.randomUUID(), "1", clock.instant()); }
        catch (IllegalStateException invalidState) { throw ApiFailure.conflict("Document is not awaiting upload or its upload window has expired"); }
        append(tenant, orderId, "DocumentProcessingRequested", "document-requests", correlation,
                new Events.DocumentProcessingRequested(document.id(), document.processingRequestId(), document.bucket(),
                        document.objectName(), document.objectGeneration().toString(), document.processorVersion()));
        journal.flush();
        return DocumentView.of(document);
    }
    public static boolean hasProcessingAttempt(OrderDocument document) {
        return document.status() == DocumentStatus.QUEUED || document.status() == DocumentStatus.PROCESSED
                || document.status() == DocumentStatus.FAILED;
    }
    private Order lockOrder(UUID tenant, UUID orderId) {
        return orders.lockByTenantIdAndId(tenant, orderId).orElseThrow(ApiFailure::notFound);
    }
    private void requireOpen(Order order) {
        if (order.status() == OrderStatus.CANCELLED || order.status() == OrderStatus.FULFILLED)
            throw ApiFailure.conflict("Documents cannot be added or queued for a terminal order");
    }
    private void append(UUID tenant, UUID aggregate, String type, String destination, UUID correlation, Object data) {
        journal.append(new OutboxEvent(UUID.randomUUID(), tenant, aggregate, type, 1, correlation, null,
                clock.instant(), "order-service", destination, json.writeValueAsString(data), com.synechisveltiosi.platform.commonobservability.TraceContext.current()));
    }
}
