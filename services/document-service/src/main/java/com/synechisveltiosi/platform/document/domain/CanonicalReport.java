package com.synechisveltiosi.platform.document.domain;

import com.synechisveltiosi.platform.eventcontracts.Events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable JSON report. The object's own generation is supplied by storage after creation. */
public record CanonicalReport(int reportVersion, Events.Envelope request, UUID resultEventId,
                              Instant completedAt, long bytesRead, String sha256, String failureCode) {
    public CanonicalReport {
        Objects.requireNonNull(request);
        Objects.requireNonNull(resultEventId);
        Objects.requireNonNull(completedAt);
        if (reportVersion != 1 || !(request.data() instanceof Events.DocumentProcessingRequested)
                || bytesRead < 0 || bytesRead > PdfMetadataProcessor.MAX_BYTES + 1)
            throw new IllegalArgumentException("Invalid report");
        if ((sha256 == null) == (failureCode == null)
                || sha256 != null && !sha256.matches("[0-9a-f]{64}")
                || failureCode != null && !failureCode.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("Invalid report outcome");
    }

    public void requireSameRequest(Events.Envelope incoming) {
        // A repeated request ID cannot be rebound to different input, tenant, document, or processor.
        if (!request.tenantId().equals(incoming.tenantId()) || !request.aggregateId().equals(incoming.aggregateId())
                || !request.data().equals(incoming.data()))
            throw new IllegalArgumentException("Processing request conflicts with canonical report");
    }

    public Events.Envelope result(String bucket, String generation) {
        var input = (Events.DocumentProcessingRequested) request.data();
        return new Events.Envelope(resultEventId, sha256 != null ? "DocumentProcessed" : "DocumentProcessingFailed",
                1, request.tenantId(), request.aggregateId(), request.correlationId(), request.eventId(), completedAt,
                "document-service", new Events.DocumentResult(input.documentId(), input.processingRequestId(),
                input.processorVersion(), bucket, Events.reportObject(request.tenantId(), input.processingRequestId()),
                generation, sha256, failureCode));
    }
}
