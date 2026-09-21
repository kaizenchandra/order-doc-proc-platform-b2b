package com.synechisveltiosi.platform.notification.adapter.messaging;

import com.synechisveltiosi.platform.eventcontracts.Events;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/** Explicit wire types; never deserialize arbitrary Java class names from event or report data. */
public final class NotificationEventCodec {
    public static final int MAX_BYTES = 64 * 1024;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.databind.cfg.JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(tools.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    public Events.Envelope decode(byte[] data) {
        if (data.length == 0 || data.length > MAX_BYTES) throw new IllegalArgumentException("Invalid event size");
        var root = json.readTree(data);
        if (!root.path("eventVersion").isIntegralNumber() || !root.path("eventVersion").canConvertToInt()
                || root.path("eventVersion").intValue() != 1)
            throw new IllegalArgumentException("Unsupported event version");
        String type = text(root, "eventType");
        var payload = root.path("data");
        Events.Data eventData = switch (type) {
            case "OrderCreated" -> json.treeToValue(payload, Events.OrderCreated.class);
            case "OrderStatusChanged" -> json.treeToValue(payload, Events.OrderStatusChanged.class);
            case "DocumentProcessed", "DocumentProcessingFailed" -> json.treeToValue(payload, Events.DocumentResult.class);
            default -> throw new IllegalArgumentException("Unsupported notification event");
        };
        return new Events.Envelope(uuid(root, "eventId"), type, 1, uuid(root, "tenantId"),
                uuid(root, "aggregateId"), uuid(root, "correlationId"), root.path("causationId").isMissingNode()
                || root.path("causationId").isNull() ? null : uuid(root, "causationId"),
                Instant.parse(text(root, "occurredAt")), text(root, "source"), eventData);
    }

    private static String text(JsonNode node, String name) {
        if (!node.path(name).isString() || node.path(name).asText().isBlank()) throw new IllegalArgumentException("Missing field");
        return node.path(name).asText();
    }

    private static UUID uuid(JsonNode node, String name) {
        String value = text(node, name);
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Invalid UUID");
        return id;
    }
}
