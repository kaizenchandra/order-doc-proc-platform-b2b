package com.synechisveltiosi.platform.document.application;

import com.synechisveltiosi.platform.document.domain.CanonicalReport;
import com.synechisveltiosi.platform.document.domain.PdfMetadataProcessor;
import com.synechisveltiosi.platform.eventcontracts.Events;

import java.time.Clock;
import java.util.UUID;

public final class DocumentProcessor {
    private final DocumentObjects objects;
    private final ReportStore reports;
    private final ResultPublisher publisher;
    private final Clock clock;
    private final String uploadBucket;
    private final String reportBucket;
    private final String processorVersion;
    private final PdfMetadataProcessor inspector = new PdfMetadataProcessor();

    public DocumentProcessor(DocumentObjects objects, ReportStore reports, ResultPublisher publisher, Clock clock,
                             String uploadBucket, String reportBucket, String processorVersion) {
        this.objects = objects;
        this.reports = reports;
        this.publisher = publisher;
        this.clock = clock;
        this.uploadBucket = uploadBucket;
        this.reportBucket = reportBucket;
        this.processorVersion = processorVersion;
    }

    public void process(Events.Envelope event) throws Exception {
        if (!(event.data() instanceof Events.DocumentProcessingRequested request)
                || !"DocumentProcessingRequested".equals(event.eventType()))
            throw new IllegalArgumentException("Expected processing request");
        if (uploadBucket.isBlank() || reportBucket.isBlank()) throw new IllegalStateException("Storage is not configured");
        if (!uploadBucket.equals(request.bucket()) || !processorVersion.equals(request.processorVersion()))
            throw new IllegalArgumentException("Unsupported input bucket or processor version");
        String path = Events.reportObject(event.tenantId(), request.processingRequestId());
        var stored = reports.find(reportBucket, path).orElse(null);
        if (stored == null) {
            PdfMetadataProcessor.Outcome outcome;
            try (var input = objects.open(request.bucket(), request.objectName(), request.generation())) {
                outcome = inspector.inspect(input.bytes(), input.contentType());
            }
            // Storage failures propagate; they must never become a terminal document failure.
            var candidate = new CanonicalReport(1, event, UUID.randomUUID(), clock.instant(), outcome.bytesRead(),
                    outcome.sha256(), outcome.failureCode());
            stored = reports.createIfAbsent(reportBucket, path, candidate);
        }
        stored.report().requireSameRequest(event);
        publisher.publish(stored.report().result(reportBucket, stored.generation()));
    }
}
