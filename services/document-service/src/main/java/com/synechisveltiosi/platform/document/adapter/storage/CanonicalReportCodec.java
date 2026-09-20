package com.synechisveltiosi.platform.document.adapter.storage;

import com.synechisveltiosi.platform.document.adapter.messaging.RequestEventCodec;
import com.synechisveltiosi.platform.document.domain.CanonicalReport;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/** JSON persistence contract for the create-only GCS report adapter. */
public final class CanonicalReportCodec {
    public static final int MAX_BYTES = 96 * 1024;
    private final RequestEventCodec requests = new RequestEventCodec();
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    public byte[] encode(CanonicalReport report) {
        byte[] bytes = json.writeValueAsBytes(report);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Report too large");
        return bytes;
    }

    public CanonicalReport decode(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Invalid report size");
        var root = json.readTree(bytes);
        if (!root.path("reportVersion").isIntegralNumber() || !root.path("reportVersion").canConvertToInt()
                || root.path("reportVersion").intValue() != 1 || !root.path("bytesRead").isIntegralNumber()
                || !root.path("bytesRead").canConvertToLong()) throw new IllegalArgumentException("Invalid report metadata");
        String id = text(root, "resultEventId", false);
        UUID eventId = UUID.fromString(id);
        if (!eventId.toString().equalsIgnoreCase(id)) throw new IllegalArgumentException("Invalid result ID");
        return new CanonicalReport(1, requests.decode(json.writeValueAsBytes(root.path("request"))), eventId,
                Instant.parse(text(root, "completedAt", false)), root.path("bytesRead").longValue(),
                text(root, "sha256", true), text(root, "failureCode", true));
    }

    private String text(JsonNode root, String field, boolean nullable) {
        var value = root.path(field);
        if (nullable && value.isNull()) return null;
        if (!value.isString()) throw new IllegalArgumentException("Invalid report field");
        return value.asText();
    }
}
