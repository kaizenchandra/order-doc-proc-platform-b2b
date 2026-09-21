package com.synechisveltiosi.platform.commonobservability;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TraceContextTest {
    private static final String TRACE = "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01";

    @Test
    void invalidAndNestedScopesRestoreTheCallerEvenOnFailure() {
        assertNull(TraceContext.current());
        try (var outer = TraceContext.open(TRACE)) {
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = TraceContext.open("untrusted")) {
                    assertNull(TraceContext.current());
                    throw new IllegalStateException("failed message");
                }
            });
            assertEquals(TRACE, TraceContext.current());
        }
        assertNull(TraceContext.current());
    }

    @Test
    void reusedWorkerCannotLeakTraceToTheNextDeliveryOrAnotherThread() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor(); var outer = TraceContext.open(TRACE)) {
            executor.submit(() -> {
                assertNull(TraceContext.current());
                try (var scope = TraceContext.open(TRACE)) {
                    assertEquals(TRACE, TraceContext.current());
                }
            }).get(5, TimeUnit.SECONDS);
            assertNull(executor.submit(TraceContext::current).get(5, TimeUnit.SECONDS));
            assertEquals(TRACE, TraceContext.current());
        }
    }

    @Test
    void rejectsZeroIdentifiersUnsupportedVersionsAndInjectedText() {
        assertTrue(TraceContext.valid(TRACE));
        for (String invalid : new String[]{null, "", TRACE.toUpperCase(), TRACE + "\r\nextra", "01" + TRACE.substring(2),
                "00-" + "0".repeat(32) + "-" + "b".repeat(16) + "-01",
                "00-" + "a".repeat(32) + "-" + "0".repeat(16) + "-01"})
            assertFalse(TraceContext.valid(invalid));
    }
}
