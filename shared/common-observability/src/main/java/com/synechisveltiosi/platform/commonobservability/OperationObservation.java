package com.synechisveltiosi.platform.commonobservability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One bounded-cardinality operational event per attempt; no payload, identifiers, or exception text. */
public final class OperationObservation implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(OperationObservation.class);
    public enum Operation { DOCUMENT_PUSH, NOTIFICATION_PUSH, ORDER_RESULT, OUTBOX_PUBLISH }
    private final Operation operation;
    private final long started = System.nanoTime();
    private boolean succeeded;
    private boolean closed;

    public OperationObservation(Operation operation) {
        this.operation = java.util.Objects.requireNonNull(operation);
    }

    public void succeeded() {
        succeeded = true;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        var event = succeeded ? LOG.atInfo() : LOG.atWarn();
        event.addKeyValue("event", "operation_completed")
                .addKeyValue("operation", operation.name())
                .addKeyValue("outcome", succeeded ? "succeeded" : "failed")
                .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000L)
                .log("Messaging operation completed");
    }
}
