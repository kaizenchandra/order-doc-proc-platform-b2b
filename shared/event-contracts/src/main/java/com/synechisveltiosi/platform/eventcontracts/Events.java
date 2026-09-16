package com.synechisveltiosi.platform.eventcontracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Version 1 wire contracts. Pure Java: no service entities or framework dependencies. */
public final class Events {
    private Events() {}
    public sealed interface Data permits OrderCreated, OrderStatusChanged, DocumentProcessingRequested, DocumentResult {}
    public record Envelope(UUID eventId, String eventType, int eventVersion, UUID tenantId, UUID aggregateId,
            UUID correlationId, UUID causationId, Instant occurredAt, String source, Data data) {
        public Envelope {
            Objects.requireNonNull(eventId); Objects.requireNonNull(tenantId); Objects.requireNonNull(aggregateId);
            Objects.requireNonNull(correlationId); Objects.requireNonNull(occurredAt); Objects.requireNonNull(data);
            if (eventVersion != 1) throw new IllegalArgumentException("Unsupported event version");
            boolean valid = switch (eventType) {
                case "OrderCreated" -> data instanceof OrderCreated d && d.orderId().equals(aggregateId) && "order-service".equals(source);
                case "OrderStatusChanged" -> data instanceof OrderStatusChanged d && d.orderId().equals(aggregateId) && "order-service".equals(source);
                case "DocumentProcessingRequested" -> data instanceof DocumentProcessingRequested && "order-service".equals(source);
                case "DocumentProcessed" -> data instanceof DocumentResult d && d.sha256() != null && d.failureCode() == null && "document-service".equals(source);
                case "DocumentProcessingFailed" -> data instanceof DocumentResult d && d.sha256() == null && d.failureCode() != null && "document-service".equals(source);
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("Invalid event type, source, or payload");
        }
    }
    public record OrderCreated(UUID orderId, UUID customerId, String status, BigDecimal totalAmount, String currency) implements Data {
        public OrderCreated {
            Objects.requireNonNull(orderId); Objects.requireNonNull(customerId); Objects.requireNonNull(totalAmount);
            if (!"CREATED".equals(status) || totalAmount.signum() <= 0 || totalAmount.scale() > 2
                    || totalAmount.setScale(2).precision() > 19 || !Set.of("USD", "EUR", "GBP", "INR").contains(currency))
                throw new IllegalArgumentException("Invalid order creation");
        }
    }
    public record OrderStatusChanged(UUID orderId, String previousStatus, String status) implements Data {
        public OrderStatusChanged {
            Objects.requireNonNull(orderId);
            boolean valid = "CREATED".equals(previousStatus) && Set.of("CONFIRMED", "CANCELLED").contains(status)
                    || "CONFIRMED".equals(previousStatus) && Set.of("FULFILLED", "CANCELLED").contains(status);
            if (!valid) throw new IllegalArgumentException("Invalid status transition");
        }
    }
    public record DocumentProcessingRequested(UUID documentId, UUID processingRequestId, String bucket,
            String objectName, String generation, String processorVersion) implements Data {
        public DocumentProcessingRequested {
            Objects.requireNonNull(documentId); Objects.requireNonNull(processingRequestId);
            text(bucket, 222); text(objectName, 512); Events.generation(generation); text(processorVersion, 32);
        }
    }
    public record DocumentResult(UUID documentId, UUID processingRequestId, String processorVersion,
            String reportBucket, String reportObjectName, String reportGeneration, String sha256, String failureCode) implements Data {
        public DocumentResult {
            Objects.requireNonNull(documentId); Objects.requireNonNull(processingRequestId); text(processorVersion, 32);
            text(reportBucket, 222); text(reportObjectName, 512); Events.generation(reportGeneration);
            if ((sha256 == null) == (failureCode == null)) throw new IllegalArgumentException("Expected one result outcome");
            if (sha256 != null && !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid checksum");
            if (failureCode != null && !failureCode.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid failure code");
        }
    }
    /** Canonical report path shared by producer and consumers. */
    public static String reportObject(UUID tenant, UUID request) { return tenant + "/" + request + "/result.json"; }
    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("Invalid text field");
    }
    private static void generation(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}") || Long.parseLong(value) <= 0)
            throw new IllegalArgumentException("Invalid object generation");
    }
}
