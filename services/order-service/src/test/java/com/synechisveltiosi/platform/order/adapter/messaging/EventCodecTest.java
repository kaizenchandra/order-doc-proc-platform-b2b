package com.synechisveltiosi.platform.order.adapter.messaging;

import com.synechisveltiosi.platform.commonobservability.TraceContext;
import com.synechisveltiosi.platform.eventcontracts.Events;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventCodecTest {
    private final EventCodec codec = new EventCodec();

    private Events.Envelope event() {
        return new Events.Envelope(UUID.randomUUID(), "DocumentProcessed", 1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                Instant.parse("2026-09-16T00:00:00Z"), "document-service",
                new Events.DocumentResult(UUID.randomUUID(), UUID.randomUUID(), "1", "reports", "path", "42", "a".repeat(64), null));
    }

    @Test
    void roundTripIsTypedAndAcceptsAdditiveFields() {
        var event = event();
        var wire = new String(codec.encode(event), StandardCharsets.UTF_8);
        assertEquals(event, codec.decode(wire.getBytes(StandardCharsets.UTF_8)));
        assertEquals(event, codec.decode((wire.substring(0, wire.length() - 1) + ",\"futureField\":true}").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void malformedDuplicateOversizedAndUnsupportedEventsFailClosed() {
        var wire = new String(codec.encode(event()), StandardCharsets.UTF_8);
        for (String invalid : new String[]{"[]", "{}", wire + "{}", wire.replace("\"eventVersion\":1", "\"eventVersion\":2"),
                wire.replace("\"eventVersion\":1", "\"eventVersion\":4294967297"),
                wire.replace("\"eventVersion\":1", "\"eventVersion\":1,\"eventVersion\":1"),
                wire.replace("DocumentProcessed", "Unknown"), wire.replace("document-service", "untrusted-service")})
            assertThrows(RuntimeException.class, () -> codec.decode(invalid.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[EventCodec.MAX_BYTES + 1]));
    }

    @Test
    void traceContextRejectsZeroIdsAndRestoresThreadState() {
        String trace = "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01";
        assertFalse(TraceContext.valid("00-" + "0".repeat(32) + "-" + "b".repeat(16) + "-01"));
        try (var ignored = TraceContext.open(trace)) {
            assertEquals(trace, TraceContext.current());
            try (var nested = TraceContext.open("bad")) {
                assertNull(TraceContext.current());
            }
            assertEquals(trace, TraceContext.current());
        }
        assertNull(TraceContext.current());
    }
}
