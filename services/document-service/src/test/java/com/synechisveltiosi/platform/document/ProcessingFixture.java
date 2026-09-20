package com.synechisveltiosi.platform.document;

import com.synechisveltiosi.platform.document.application.*;
import com.synechisveltiosi.platform.document.domain.CanonicalReport;
import com.synechisveltiosi.platform.eventcontracts.Events;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

class ProcessingFixture implements DocumentObjects, ReportStore, ResultPublisher {
    static final byte[] PDF = "%PDF-1.7\nmetadata fixture\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    final Map<String, Stored> reports = new ConcurrentHashMap<>();
    final List<Events.Envelope> published = new CopyOnWriteArrayList<>();
    final AtomicInteger opens = new AtomicInteger();
    volatile byte[] input = PDF;
    volatile String contentType = "application/pdf";
    volatile boolean failPublish;
    volatile boolean failRead;
    volatile boolean failWrite;

    Events.Envelope request() {
        return new Events.Envelope(UUID.randomUUID(), "DocumentProcessingRequested", 1, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, Instant.parse("2026-09-21T00:00:00Z"), "order-service",
                new Events.DocumentProcessingRequested(UUID.randomUUID(), UUID.randomUUID(), "uploads", "input.pdf", "42", "1"));
    }

    DocumentProcessor processor() {
        return new DocumentProcessor(this, this, this, Clock.fixed(Instant.parse("2026-09-21T00:00:01Z"), ZoneOffset.UTC),
                "uploads", "reports", "1");
    }

    @Override public Input open(String bucket, String object, String generation) throws IOException {
        if (!"uploads".equals(bucket) || !"input.pdf".equals(object) || !"42".equals(generation))
            throw new AssertionError("Must read the exact input generation");
        opens.incrementAndGet();
        if (failRead) throw new IOException("temporary read failure");
        return new Input(new ByteArrayInputStream(input), contentType);
    }

    @Override public Optional<Stored> find(String bucket, String path) {
        return Optional.ofNullable(reports.get(bucket + "/" + path));
    }

    @Override public Stored createIfAbsent(String bucket, String path, CanonicalReport report) throws IOException {
        if (failWrite) throw new IOException("temporary write failure");
        return reports.computeIfAbsent(bucket + "/" + path, ignored -> new Stored(report, "123"));
    }

    @Override public void publish(Events.Envelope event) throws IOException {
        if (reports.isEmpty()) throw new AssertionError("Must persist before publishing");
        if (failPublish) throw new IOException("broker unavailable");
        published.add(event);
    }

    void reset() {
        reports.clear();
        published.clear();
        opens.set(0);
        input = PDF;
        contentType = "application/pdf";
        failPublish = failRead = failWrite = false;
    }
}
