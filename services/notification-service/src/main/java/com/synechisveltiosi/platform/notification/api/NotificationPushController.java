package com.synechisveltiosi.platform.notification.api;

import com.synechisveltiosi.platform.commonobservability.TraceContext;
import com.synechisveltiosi.platform.notification.application.NotificationHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class NotificationPushController {
    private static final Logger log = LoggerFactory.getLogger(NotificationPushController.class);
    private final PushCodec codec;
    private final NotificationHandler processor;
    private final String subscription;

    public NotificationPushController(PushCodec codec, NotificationHandler processor,
                                  @Value("${app.push.subscription}") String subscription) {
        if (java.util.Arrays.stream(subscription.split(",", -1)).anyMatch(value -> !value.matches("projects/[^/]+/subscriptions/[^/]+")))
            throw new IllegalArgumentException("Push subscription is required");
        this.codec = codec;
        this.processor = processor;
        this.subscription = subscription;
    }

    @PostMapping(path = "/internal/pubsub/events", consumes = "application/json")
    public ResponseEntity<Void> receive(HttpServletRequest request) {
        try {
            // Also bounds chunked requests; Content-Length alone is not a safe size check.
            byte[] body = request.getInputStream().readNBytes(PushCodec.MAX_PUSH_BYTES + 1);
            if (body.length > PushCodec.MAX_PUSH_BYTES) return ResponseEntity.status(413).build();
            var delivery = codec.decode(body, subscription);
            try (var ignored = TraceContext.open(delivery.traceparent())) {
                processor.handle(delivery.event());
            }
            return ResponseEntity.noContent().build();
        } catch (Exception failure) {
            // Every failure is non-2xx: poison messages must remain eligible for retry/dead-letter handling.
            // Do not log raw payloads, object names, credentials, or SDK exception messages.
            log.warn("Notification delivery failed; failureType={}", failure.getClass().getSimpleName());
            return ResponseEntity.status(503).build();
        }
    }
}
