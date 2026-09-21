package com.synechisveltiosi.platform.commonobservability;

/**
 * W3C v00 propagation and structured-log correlation; does not create or export spans.
 */
public final class TraceContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    public static String current() {
        return CURRENT.get();
    }

    public static boolean valid(String value) {
        return value != null && value.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                && !value.substring(3, 35).equals("0".repeat(32)) && !value.substring(36, 52).equals("0".repeat(16));
    }

    public static Scope open(String value) {
        String previous = CURRENT.get();
        String previousTrace = org.slf4j.MDC.get("trace_id");
        String previousSpan = org.slf4j.MDC.get("span_id");
        if (valid(value)) {
            org.slf4j.MDC.put("trace_id", value.substring(3, 35));
            org.slf4j.MDC.put("span_id", value.substring(36, 52));
        } else {
            org.slf4j.MDC.remove("trace_id");
            org.slf4j.MDC.remove("span_id");
        }
        if (valid(value)) CURRENT.set(value);
        else CURRENT.remove();
        return () -> {
            restoreMdc("trace_id", previousTrace);
            restoreMdc("span_id", previousSpan);
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    private static void restoreMdc(String key, String value) {
        if (value == null) org.slf4j.MDC.remove(key);
        else org.slf4j.MDC.put(key, value);
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
