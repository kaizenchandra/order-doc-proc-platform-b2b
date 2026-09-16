package com.synechisveltiosi.platform.eventcontracts;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventsTest {
    @Test
    void resultRequiresExactlyOneValidOutcome() {
        var doc = UUID.randomUUID();
        var request = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new Events.DocumentResult(doc, request, "1", "reports", "x", "42", null, null));
        assertThrows(IllegalArgumentException.class, () -> new Events.DocumentResult(doc, request, "1", "reports", "x", "42", "a".repeat(64), "ERROR"));
        assertThrows(IllegalArgumentException.class, () -> new Events.DocumentResult(doc, request, "1", "reports", "x", "42", "bad", null));
        assertThrows(IllegalArgumentException.class, () -> new Events.DocumentResult(doc, request, "1", "reports", "x", "42", null, "error details"));
    }

    @Test
    void envelopeRejectsWrongSourceVersionAndOutcomeType() {
        var result = new Events.DocumentResult(UUID.randomUUID(), UUID.randomUUID(), "1", "reports", "x", "42", "a".repeat(64), null);
        assertThrows(IllegalArgumentException.class, () -> envelope("DocumentProcessed", 2, "document-service", result));
        assertThrows(IllegalArgumentException.class, () -> envelope("DocumentProcessed", 1, "order-service", result));
        assertThrows(IllegalArgumentException.class, () -> envelope("DocumentProcessingFailed", 1, "document-service", result));
        assertDoesNotThrow(() -> envelope("DocumentProcessed", 1, "document-service", result));
    }

    @Test
    void generationMustBePositiveCanonicalAndFitSignedLong() {
        for (var generation : new String[]{"0", "-1", "1.2", "01", "9223372036854775808"})
            assertThrows(IllegalArgumentException.class, () -> new Events.DocumentProcessingRequested(UUID.randomUUID(), UUID.randomUUID(), "uploads", "object", generation, "1"));
    }

    private Events.Envelope envelope(String type, int version, String source, Events.Data data) {
        return new Events.Envelope(UUID.randomUUID(), type, version, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, Instant.now(), source, data);
    }
}
