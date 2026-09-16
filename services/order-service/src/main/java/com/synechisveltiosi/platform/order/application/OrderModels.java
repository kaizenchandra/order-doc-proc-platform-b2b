package com.synechisveltiosi.platform.order.application;

import com.synechisveltiosi.platform.order.domain.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class OrderModels {
    private OrderModels() {}
    public record CreateOrder(@NotNull UUID customerId,
            @NotBlank @Size(max = 100) String customerReference,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal totalAmount,
            @NotNull @Pattern(regexp = "USD|EUR|GBP|INR") String currency) {}
    public record ChangeStatus(@NotNull OrderStatus status, @NotNull @PositiveOrZero Long expectedVersion) {}
    public record RegisterDocument(@NotBlank @Size(max = 255) String fileName,
            @NotBlank @Size(max = 100) String contentType) {}
    public record OrderView(UUID id, UUID customerId, String customerReference, BigDecimal totalAmount,
            String currency, OrderStatus status, Instant createdAt, Instant updatedAt, Long version) {
        public static OrderView of(Order o) {
            return new OrderView(o.id(), o.customerId(), o.customerReference(), o.totalAmount(), o.currency(),
                    o.status(), o.createdAt(), o.updatedAt(), o.version());
        }
    }
    public record DocumentView(UUID id, UUID orderId, String fileName, String contentType,
            DocumentStatus status, Instant uploadExpiresAt, UUID processingRequestId,
            Long sizeBytes, String sha256, String failureCode, Instant completedAt, Long version) {
        public static DocumentView of(OrderDocument d) {
            return new DocumentView(d.id(), d.orderId(), d.fileName(), d.declaredContentType(), d.status(),
                    d.uploadExpiresAt(), d.processingRequestId(), d.sizeBytes(), d.sha256(), d.failureCode(),
                    d.completedAt(), d.version());
        }
    }
    public record StoredResponse(UUID resourceId, String body) {}
    public record Registration(DocumentView document, DocumentStorage.UploadAuthorization upload) {}
}
