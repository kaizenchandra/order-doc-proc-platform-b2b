package com.synechisveltiosi.platform.order.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OrderDomainTest {
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private Order order(BigDecimal amount) {
        return Order.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PO-123", amount, "INR", NOW);
    }
    private OrderDocument document() {
        return OrderDocument.register(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "invoice.pdf",
                "application/pdf", "uploads", "tenant/order/document", NOW.plusSeconds(600), NOW);
    }
    private VerifiedUpload upload() {
        return new VerifiedUpload(new GcsObjectReference("uploads", "tenant/order/document", 7), 512);
    }

    @Test void moneyIsExactAndNeverSilentlyRounded() {
        assertEquals(new BigDecimal("100.00"), order(new BigDecimal("100")).totalAmount());
        assertThrows(ArithmeticException.class, () -> order(new BigDecimal("100.001")));
        assertThrows(IllegalArgumentException.class, () -> order(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> order(new BigDecimal("100000000000000000.00")));
    }

    @Test void terminalOrdersCannotBeReopened() {
        var order = order(BigDecimal.TEN);
        assertThrows(IllegalStateException.class, () -> order.transitionTo(OrderStatus.FULFILLED, NOW));
        assertTrue(order.transitionTo(OrderStatus.CONFIRMED, NOW));
        assertFalse(order.transitionTo(OrderStatus.CONFIRMED, NOW));
        assertTrue(order.transitionTo(OrderStatus.FULFILLED, NOW));
        assertThrows(IllegalStateException.class, () -> order.transitionTo(OrderStatus.CANCELLED, NOW));
    }

    @Test void uploadMustMatchRegistrationAndBeWithinItsWindow() {
        var doc = document();
        var wrong = new VerifiedUpload(new GcsObjectReference("uploads", "someone-elses-file", 7), 512);
        assertThrows(IllegalArgumentException.class, () -> doc.queue(wrong, UUID.randomUUID(), "1", NOW));
        assertEquals(DocumentStatus.AWAITING_UPLOAD, doc.status());
        assertThrows(IllegalStateException.class, () -> doc.queue(upload(), UUID.randomUUID(), "1", NOW.plusSeconds(600)));
        doc.expire(NOW.plusSeconds(600));
        assertEquals(DocumentStatus.EXPIRED, doc.status());
    }

    @Test void staleResultsCannotOverwriteReprocessingAndTerminalResultsAreStable() {
        var doc = document();
        var oldRequest = UUID.randomUUID();
        var newRequest = UUID.randomUUID();
        var report = new GcsObjectReference("reports", "result.json", 8);
        doc.queue(upload(), oldRequest, "1", NOW);
        assertTrue(doc.fail(oldRequest, report, "UNSUPPORTED_FORMAT", NOW));
        assertThrows(IllegalArgumentException.class, () -> doc.reprocess(oldRequest, "2", NOW));
        doc.reprocess(newRequest, "2", NOW.plusSeconds(1));
        assertNull(doc.failureCode());
        assertNull(doc.reportGeneration());
        assertFalse(doc.succeed(oldRequest, report, "a".repeat(64), NOW.plusSeconds(2)));
        assertEquals(DocumentStatus.QUEUED, doc.status());
        assertTrue(doc.succeed(newRequest, report, "a".repeat(64), NOW.plusSeconds(2)));
        assertFalse(doc.fail(newRequest, report, "LATE_FAILURE", NOW.plusSeconds(3)));
        assertEquals(DocumentStatus.PROCESSED, doc.status());
    }

    @Test void invalidResultCannotPartiallyMutateTheDocument() {
        var doc = document();
        var request = UUID.randomUUID();
        doc.queue(upload(), request, "1", NOW);
        assertThrows(IllegalArgumentException.class, () -> doc.succeed(request,
                new GcsObjectReference("reports", "result.json", 8), "not-a-checksum", NOW));
        assertEquals(DocumentStatus.QUEUED, doc.status());
        assertNull(doc.reportBucket());
        assertNull(doc.completedAt());
        assertThrows(IllegalArgumentException.class, () -> new VerifiedUpload(upload().object(), VerifiedUpload.MAX_SIZE_BYTES + 1));
    }
}
