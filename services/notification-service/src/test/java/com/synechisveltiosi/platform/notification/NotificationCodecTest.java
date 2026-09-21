package com.synechisveltiosi.platform.notification;

import com.synechisveltiosi.platform.eventcontracts.Events;
import com.synechisveltiosi.platform.notification.adapter.messaging.NotificationEventCodec;
import com.synechisveltiosi.platform.notification.api.PushCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class NotificationCodecTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final NotificationEventCodec codec = new NotificationEventCodec();
    private final UUID order = UUID.randomUUID();

    private Events.Envelope event(String type, Events.Data data) {
        return new Events.Envelope(UUID.randomUUID(), type, 1, UUID.randomUUID(), order, UUID.randomUUID(),
                null, Instant.now(), type.startsWith("Order") ? "order-service" : "document-service", data);
    }

    @Test
    void supportsAllFourNotificationContracts() {
        var events = List.of(event("OrderCreated", new Events.OrderCreated(order, UUID.randomUUID(), "CREATED", new BigDecimal("99999999999999999.99"), "USD")),
                event("OrderStatusChanged", new Events.OrderStatusChanged(order, "CREATED", "CONFIRMED")),
                event("DocumentProcessed", new Events.DocumentResult(UUID.randomUUID(), UUID.randomUUID(), "v1", "reports", "result.json", "1", "a".repeat(64), null)),
                event("DocumentProcessingFailed", new Events.DocumentResult(UUID.randomUUID(), UUID.randomUUID(), "v1", "reports", "result.json", "1", null, "INVALID_PDF")));
        for (var event : events) assertEquals(event, codec.decode(json.writeValueAsBytes(event)));
    }

    @Test
    void rejectsUnsupportedInvalidAndAmbiguousEvents() {
        String wire = json.writeValueAsString(event("OrderStatusChanged", new Events.OrderStatusChanged(order, "CREATED", "CONFIRMED")));
        for (String invalid : List.of(wire.replace("OrderStatusChanged", "Unknown"),
                wire.replace("\"eventVersion\":1", "\"eventVersion\":2"),
                wire.replace("order-service", "document-service"), wire.replace("CONFIRMED", "FULFILLED"),
                wire + " {}", wire.replace("\"eventVersion\":1", "\"eventVersion\":1,\"eventVersion\":1")))
            assertThrows(RuntimeException.class, () -> codec.decode(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(RuntimeException.class, () -> codec.decode(new byte[65537]));
    }

    @Test
    void checksSubscriptionAndBase64BeforeProcessing() {
        var event = event("OrderStatusChanged", new Events.OrderStatusChanged(order, "CREATED", "CONFIRMED"));
        String subscription = "projects/test/subscriptions/orders";
        byte[] body = json.writeValueAsBytes(Map.of("subscription", subscription, "message",
                Map.of("data", Base64.getEncoder().encodeToString(json.writeValueAsBytes(event)))));
        assertEquals(event, new PushCodec().decode(body, subscription + ",projects/test/subscriptions/results").event());
        assertThrows(RuntimeException.class, () -> new PushCodec().decode(body, "projects/test/subscriptions/other"));
        assertThrows(RuntimeException.class, () -> new PushCodec().decode(json.writeValueAsBytes(Map.of(
                "subscription", subscription, "message", Map.of("data", "%%%"))), subscription));
    }
}
