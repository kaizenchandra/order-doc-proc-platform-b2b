package com.synechisveltiosi.platform.commonobservability;

/** W3C v00 traceparent propagation only; span creation/export is a later phase. */
public final class TraceContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private TraceContext() {}
    public static String current() { return CURRENT.get(); }
    public static boolean valid(String value) {
        return value != null && value.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                && !value.substring(3, 35).equals("0".repeat(32)) && !value.substring(36, 52).equals("0".repeat(16));
    }
    public static Scope open(String value) {
        String previous = CURRENT.get();
        if (valid(value)) CURRENT.set(value); else CURRENT.remove();
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
