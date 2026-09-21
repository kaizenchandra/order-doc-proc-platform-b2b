package com.synechisveltiosi.platform.document;

import com.synechisveltiosi.platform.document.adapter.storage.CanonicalReportCodec;
import com.synechisveltiosi.platform.document.domain.CanonicalReport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalReportCodecTest {
    private final CanonicalReportCodec codec = new CanonicalReportCodec();

    @Test
    void persistedSuccessAndFailureRebuildTheSameResultEvent() {
        for (boolean success : new boolean[]{true, false}) {
            var report = new CanonicalReport(1, new ProcessingFixture().request(), UUID.randomUUID(), Instant.now(), 32,
                    success ? "a".repeat(64) : null, success ? null : "INVALID_PDF");
            var decoded = codec.decode(codec.encode(report));
            assertEquals(report, decoded);
            assertEquals(report.result("reports", "123"), decoded.result("reports", "123"));
        }
    }

    @Test
    void corruptAndUnsupportedReportsCannotBeReplayed() {
        var report = new CanonicalReport(1, new ProcessingFixture().request(), UUID.randomUUID(), Instant.now(), 32,
                "a".repeat(64), null);
        String wire = new String(codec.encode(report), StandardCharsets.UTF_8);
        for (String invalid : new String[]{"{}", wire + "{}",
                wire.replace("\"reportVersion\":1", "\"reportVersion\":2"),
                wire.replace("\"reportVersion\":1", "\"reportVersion\":1,\"reportVersion\":1"),
                wire.replace("\"bytesRead\":32", "\"bytesRead\":-1"),
                wire.replace("\"bytesRead\":32", "\"bytesRead\":1.5"),
                wire.replace("\"bytesRead\":32", "\"bytesRead\":999999999"),
                wire.replace("\"failureCode\":null", "\"failureCode\":\"INVALID_PDF\"")})
            assertThrows(RuntimeException.class, () -> codec.decode(invalid.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[CanonicalReportCodec.MAX_BYTES + 1]));
    }
}
