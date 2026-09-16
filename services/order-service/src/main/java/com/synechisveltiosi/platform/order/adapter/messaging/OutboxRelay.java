package com.synechisveltiosi.platform.order.adapter.messaging;

import com.synechisveltiosi.platform.order.adapter.persistence.OutboxStore;
import com.synechisveltiosi.platform.order.config.MessagingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Claims one fresh lease per publish, avoiding a waiting batch whose leases expire before use.
 */
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxStore store;
    private final EventCodec codec;
    private final EventPublisher publisher;
    private final MessagingProperties settings;

    public OutboxRelay(OutboxStore store, EventCodec codec, EventPublisher publisher, MessagingProperties settings) {
        this.store = store;
        this.codec = codec;
        this.publisher = publisher;
        this.settings = settings;
    }

    public int poll() {
        int attempted = 0;
        while (attempted < settings.maxPerPoll() && !Thread.currentThread().isInterrupted()) {
            var candidate = store.claim();
            if (candidate.isEmpty()) break;
            var lease = candidate.get();
            attempted++;
            try {
                var event = lease.envelope(codec);
                String expected = event.eventType().equals("DocumentProcessingRequested") ? "document-requests" : "order-events";
                if (!event.source().equals("order-service") || !expected.equals(lease.destination()))
                    throw new IllegalArgumentException("Unexpected outbox route");
                var attributes = new HashMap<String, String>();
                attributes.put("eventId", event.eventId().toString());
                attributes.put("correlationId", event.correlationId().toString());
                if (com.synechisveltiosi.platform.commonobservability.TraceContext.valid(lease.traceparent()))
                    attributes.put("traceparent", lease.traceparent());
                publisher.publish(lease.destination(), codec.encode(event), attributes);
            } catch (Exception failure) {
                int cap = Math.min(300, 1 << Math.min(lease.attempt(), 9));
                store.retry(lease, ThreadLocalRandom.current().nextInt(Math.max(1, cap / 2), cap + 1),
                        failure instanceof IllegalArgumentException ? "INVALID_EVENT" : "PUBLISH_FAILED");
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
                log.warn("Outbox publication failed; eventId={}, attempt={}", lease.eventId(), lease.attempt());
                continue;
            }
            // A DB failure here leaves the lease for recovery and can cause duplicate publication.
            if (!store.published(lease)) log.warn("Outbox lease expired or was replaced; eventId={}", lease.eventId());
        }
        return attempted;
    }
}
