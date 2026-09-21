package com.synechisveltiosi.platform.document;

import com.synechisveltiosi.platform.document.api.PushCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PushCodecTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final PushCodec codec = new PushCodec();
    private final String subscription = "projects/test/subscriptions/requests";

    private byte[] wrap(String data) {
        return json.writeValueAsBytes(Map.of("subscription", subscription, "message", Map.of("data",
                Base64.getEncoder().encodeToString(data.getBytes(StandardCharsets.UTF_8)))));
    }

    @Test
    void roundTripPreservesRequestAndToleratesAdditiveFields() {
        var request = new ProcessingFixture().request();
        String wire = json.writeValueAsString(request);
        assertEquals(request, codec.decode(wrap(wire), subscription).event());
        assertEquals(request, codec.decode(wrap(wire.substring(0, wire.length() - 1) + ",\"future\":true}"), subscription).event());
    }

    @Test
    void unsupportedVersionsTypesSourcesAndAmbiguousJsonAreRejected() {
        String wire = json.writeValueAsString(new ProcessingFixture().request());
        for (String invalid : new String[]{"{}", "[]", wire + "{}",
                wire.replace("\"eventVersion\":1", "\"eventVersion\":2"),
                wire.replace("\"eventVersion\":1", "\"eventVersion\":4294967297"),
                wire.replace("\"eventVersion\":1", "\"eventVersion\":1,\"eventVersion\":1"),
                wire.replace("DocumentProcessingRequested", "DocumentProcessed"), wire.replace("order-service", "other"),
                wire.replace("\"generation\":\"42\"", "\"generation\":42")})
            assertThrows(RuntimeException.class, () -> codec.decode(wrap(invalid), subscription));
    }

    @Test
    void invalidBase64AndOversizedDecodedPayloadFailClosed() {
        assertThrows(RuntimeException.class, () -> codec.decode(json.writeValueAsBytes(Map.of("subscription", subscription,
                "message", Map.of("data", "%%%"))), subscription));
        assertThrows(RuntimeException.class, () -> codec.decode(wrap("a".repeat(PushCodec.MAX_EVENT_BYTES + 1)), subscription));
    }
}
