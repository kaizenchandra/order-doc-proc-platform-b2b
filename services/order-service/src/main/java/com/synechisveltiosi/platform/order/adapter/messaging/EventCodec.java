package com.synechisveltiosi.platform.order.adapter.messaging;

import com.synechisveltiosi.platform.eventcontracts.Events;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

@Component
public class EventCodec {
    public static final int MAX_BYTES = 64 * 1024;
    // Additive fields are tolerated within v1; polymorphic Java class names are never accepted from wire data.
    private final ObjectMapper json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.databind.cfg.JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(tools.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();

    public byte[] encode(Events.Envelope event) {
        byte[] bytes = json.writeValueAsBytes(event);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Event exceeds size limit");
        return bytes;
    }

    public Events.Envelope decode(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Invalid event size");
        JsonNode root = json.readTree(bytes);
        if (!root.isObject() || !root.path("eventVersion").isIntegralNumber() || !root.path("eventVersion").canConvertToInt() || root.path("eventVersion").intValue() != 1)
            throw new IllegalArgumentException("Unsupported event version");
        String type = text(root, "eventType");
        var data = payload(type, root.path("data"));
        return new Events.Envelope(uuid(root, "eventId"), type, 1, uuid(root, "tenantId"), uuid(root, "aggregateId"),
                uuid(root, "correlationId"), root.path("causationId").isMissingNode() || root.path("causationId").isNull() ? null : uuid(root, "causationId"),
                Instant.parse(text(root, "occurredAt")), text(root, "source"), data);
    }

    public Events.Data payload(String type, String data) {
        return payload(type, json.readTree(data));
    }

    private Events.Data payload(String type, JsonNode data) {
        if (!data.isObject()) throw new IllegalArgumentException("Event data must be an object");
        Class<? extends Events.Data> target = switch (type) {
            case "OrderCreated" -> Events.OrderCreated.class;
            case "OrderStatusChanged" -> Events.OrderStatusChanged.class;
            case "DocumentProcessingRequested" -> Events.DocumentProcessingRequested.class;
            case "DocumentProcessed", "DocumentProcessingFailed" -> Events.DocumentResult.class;
            default -> throw new IllegalArgumentException("Unsupported event type");
        };
        return json.treeToValue(data, target);
    }

    private String text(JsonNode root, String field) {
        if (!root.path(field).isString()) throw new IllegalArgumentException("Missing event field");
        return root.path(field).asText();
    }

    private UUID uuid(JsonNode root, String field) {
        String value = text(root, field);
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Invalid UUID");
        return id;
    }
}
