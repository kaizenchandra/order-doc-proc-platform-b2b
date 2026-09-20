package com.synechisveltiosi.platform.document.api;

import com.synechisveltiosi.platform.eventcontracts.Events;
import com.synechisveltiosi.platform.document.adapter.messaging.RequestEventCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;

@Component
public class PushCodec {
    public static final int MAX_EVENT_BYTES = RequestEventCodec.MAX_BYTES;
    public static final int MAX_PUSH_BYTES = 96 * 1024;
    private final RequestEventCodec requests = new RequestEventCodec();
    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    public Delivery decode(byte[] body, String subscription) {
        if (body.length == 0 || body.length > MAX_PUSH_BYTES) throw new IllegalArgumentException("Invalid push size");
        var wrapper = json.readTree(body);
        if (!subscription.equals(text(wrapper, "subscription"))) throw new IllegalArgumentException("Unexpected subscription");
        var message = wrapper.path("message");
        String encoded = text(message, "data");
        if (encoded.length() > 4 * ((MAX_EVENT_BYTES + 2) / 3)) throw new IllegalArgumentException("Event too large");
        byte[] data = Base64.getDecoder().decode(encoded);
        var event = requests.decode(data);
        var trace = message.path("attributes").path("traceparent");
        return new Delivery(event, trace.isString() ? trace.asText() : null);
    }

    private static String text(JsonNode node, String name) {
        if (!node.path(name).isString() || node.path(name).asText().isBlank()) throw new IllegalArgumentException("Missing field");
        return node.path(name).asText();
    }

    public record Delivery(Events.Envelope event, String traceparent) { }
}
