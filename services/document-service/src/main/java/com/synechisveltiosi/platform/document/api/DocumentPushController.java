package com.synechisveltiosi.platform.document.api;

import com.synechisveltiosi.platform.commonobservability.TraceContext;
import com.synechisveltiosi.platform.document.application.DocumentProcessor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DocumentPushController {
    private static final Logger log = LoggerFactory.getLogger(DocumentPushController.class);
    private final PushCodec codec;
    private final DocumentProcessor processor;
    private final String subscription;

    public DocumentPushController(PushCodec codec, DocumentProcessor processor,
                                  @Value("${app.push.subscription}") String subscription) {
        if (!subscription.matches("projects/[^/]+/subscriptions/[^/]+"))
            throw new IllegalArgumentException("Push subscription is required");
        this.codec = codec;
        this.processor = processor;
        this.subscription = subscription;
    }

    @PostMapping(path = "/internal/pubsub/document-requests", consumes = "application/json")
    public ResponseEntity<Void> receive(HttpServletRequest request) {
        try {
            // Also bounds chunked requests; Content-Length alone is not a safe size check.
            byte[] body = request.getInputStream().readNBytes(PushCodec.MAX_PUSH_BYTES + 1);
            if (body.length > PushCodec.MAX_PUSH_BYTES) return ResponseEntity.status(413).build();
            var delivery = codec.decode(body, subscription);
            try (var ignored = TraceContext.open(delivery.traceparent())) {
                processor.process(delivery.event());
            }
            return ResponseEntity.noContent().build();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(503).build();
        } catch (Exception failure) {
            // Every failure is non-2xx: poison messages must remain eligible for retry/dead-letter handling.
            // Do not log raw payloads, object names, credentials, or SDK exception messages.
            log.warn("Document delivery failed; failureType={}", failure.getClass().getSimpleName());
            return ResponseEntity.status(503).build();
        }
    }
}
