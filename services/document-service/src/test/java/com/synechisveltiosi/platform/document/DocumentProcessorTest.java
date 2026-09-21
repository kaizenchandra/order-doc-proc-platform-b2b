package com.synechisveltiosi.platform.document;

import com.synechisveltiosi.platform.document.application.*;
import com.synechisveltiosi.platform.document.domain.PdfMetadataProcessor;
import com.synechisveltiosi.platform.eventcontracts.Events;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DocumentProcessorTest {
    @Test
    void successPersistsBeforePublicationAndRedeliveryUsesStableResult() throws Exception {
        var fixture = new ProcessingFixture();
        var request = fixture.request();
        fixture.processor().process(request);
        var first = fixture.published.getFirst();
        fixture.input = new byte[0]; // A redelivery must not reread even if the original input is no longer available.
        fixture.processor().process(request);
        assertEquals(first, fixture.published.getLast());
        assertEquals(1, fixture.opens.get());
        assertEquals(request.eventId(), first.causationId());
        assertEquals(request.correlationId(), first.correlationId());
        var data = (Events.DocumentResult) first.data();
        assertEquals("123", data.reportGeneration());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ProcessingFixture.PDF)), data.sha256());
    }

    @Test
    void publicationFailureLeavesDurableReportForRetry() throws Exception {
        var fixture = new ProcessingFixture();
        var request = fixture.request();
        fixture.failPublish = true;
        assertThrows(IOException.class, () -> fixture.processor().process(request));
        var stored = fixture.reports.values().iterator().next();
        fixture.failPublish = false;
        fixture.processor().process(request);
        assertEquals(stored.report().resultEventId(), fixture.published.getFirst().eventId());
        assertEquals(1, fixture.opens.get());
    }

    @Test
    void ambiguousWriteSuccessIsRecoveredByReadingCanonicalReport() throws Exception {
        var fixture = new ProcessingFixture();
        var request = fixture.request();
        ReportStore ambiguous = new ReportStore() {
            public java.util.Optional<Stored> find(String bucket, String path) { return fixture.find(bucket, path); }
            public Stored createIfAbsent(String bucket, String path, com.synechisveltiosi.platform.document.domain.CanonicalReport report) throws IOException {
                fixture.createIfAbsent(bucket, path, report);
                throw new IOException("response lost after write");
            }
        };
        var processor = new DocumentProcessor(fixture, ambiguous, fixture, Clock.systemUTC(), "uploads", "reports", "1");
        assertThrows(IOException.class, () -> processor.process(request));
        assertTrue(fixture.published.isEmpty());
        processor.process(request);
        assertEquals(1, fixture.opens.get());
        assertEquals(1, fixture.published.size());
    }

    @Test
    void concurrentWorkersPublishOnlyTheWinningReport() throws Exception {
        var fixture = new ProcessingFixture();
        var request = fixture.request();
        var gate = new CyclicBarrier(4);
        DocumentObjects objects = (bucket, name, generation) -> {
            try { gate.await(5, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new IOException(failure); }
            return fixture.open(bucket, name, generation);
        };
        var processor = new DocumentProcessor(objects, fixture, fixture, Clock.systemUTC(), "uploads", "reports", "1");
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> { processor.process(request); return null; }));
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, fixture.reports.size());
        assertEquals(4, fixture.published.size());
        assertEquals(1, fixture.published.stream().distinct().count());
    }

    @Test
    void invalidBytesProduceStableFailureButStorageErrorsRemainRetryable() throws Exception {
        var fixture = new ProcessingFixture();
        fixture.input = "not a PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        fixture.processor().process(fixture.request());
        assertEquals("DocumentProcessingFailed", fixture.published.getFirst().eventType());
        assertEquals("INVALID_PDF", ((Events.DocumentResult) fixture.published.getFirst().data()).failureCode());
        fixture.reset();
        fixture.failRead = true;
        assertThrows(IOException.class, () -> fixture.processor().process(fixture.request()));
        assertTrue(fixture.reports.isEmpty());
        assertTrue(fixture.published.isEmpty());
        fixture.failRead = false;
        fixture.failWrite = true;
        assertThrows(IOException.class, () -> fixture.processor().process(fixture.request()));
        assertTrue(fixture.published.isEmpty());
    }

    @Test
    void reboundProcessingRequestCannotPublishAnotherDocumentsReport() throws Exception {
        var fixture = new ProcessingFixture();
        var first = fixture.request();
        fixture.processor().process(first);
        var data = (Events.DocumentProcessingRequested) first.data();
        var conflict = new Events.Envelope(UUID.randomUUID(), first.eventType(), 1, first.tenantId(), first.aggregateId(),
                first.correlationId(), null, first.occurredAt(), first.source(), new Events.DocumentProcessingRequested(
                UUID.randomUUID(), data.processingRequestId(), data.bucket(), data.objectName(), data.generation(), "1"));
        assertThrows(IllegalArgumentException.class, () -> fixture.processor().process(conflict));
        assertEquals(1, fixture.published.size());
    }

    @Test
    void unsupportedBucketAndVersionAreRejectedBeforeStorage() {
        var fixture = new ProcessingFixture();
        var original = fixture.request();
        var data = (Events.DocumentProcessingRequested) original.data();
        for (var input : new String[][]{{"other", "1"}, {"uploads", "2"}}) {
            var request = new Events.Envelope(original.eventId(), original.eventType(), 1, original.tenantId(), original.aggregateId(),
                    original.correlationId(), null, original.occurredAt(), original.source(), new Events.DocumentProcessingRequested(
                    data.documentId(), data.processingRequestId(), input[0], data.objectName(), data.generation(), input[1]));
            assertThrows(IllegalArgumentException.class, () -> fixture.processor().process(request));
        }
        assertEquals(0, fixture.opens.get());
        assertTrue(fixture.reports.isEmpty());
    }

    @Test
    void processorBoundsReadsAndClassifiesEmptyAndUnsupportedInput() throws Exception {
        var inspector = new PdfMetadataProcessor();
        assertEquals("EMPTY_DOCUMENT", inspector.inspect(new ByteArrayInputStream(new byte[0]), "application/pdf").failureCode());
        assertEquals("UNSUPPORTED_FORMAT", inspector.inspect(InputStream.nullInputStream(), "image/png").failureCode());
        var count = new AtomicLong();
        InputStream endless = new InputStream() {
            public int read() { count.incrementAndGet(); return 65; }
            public int read(byte[] buffer, int offset, int length) {
                java.util.Arrays.fill(buffer, offset, offset + length, (byte) 65);
                count.addAndGet(length);
                return length;
            }
        };
        assertEquals("DOCUMENT_TOO_LARGE", inspector.inspect(endless, "application/pdf").failureCode());
        assertEquals(PdfMetadataProcessor.MAX_BYTES + 1, count.get());
    }
    @Test
    void midStreamFailureClosesInputAndLeavesNoTerminalReport() throws Exception {
        var fixture = new ProcessingFixture();
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        DocumentObjects failing = (bucket, name, generation) -> new DocumentObjects.Input(new InputStream() {
            private int reads;
            public int read() throws IOException {
                if (++reads > 16) throw new IOException("connection lost mid-stream");
                return 'a';
            }
            public void close() { closed.set(true); }
        }, "application/pdf");
        var processor = new DocumentProcessor(failing, fixture, fixture, Clock.systemUTC(), "uploads", "reports", "1");
        var request = fixture.request();
        assertThrows(IOException.class, () -> processor.process(request));
        assertTrue(closed.get());
        assertTrue(fixture.reports.isEmpty());
        assertTrue(fixture.published.isEmpty());
        fixture.processor().process(request);
        assertEquals("DocumentProcessed", fixture.published.getFirst().eventType());
    }

    @Test
    void closeFailureMustNotCommitOrPublishAResult() {
        var fixture = new ProcessingFixture();
        DocumentObjects failing = (bucket, name, generation) -> new DocumentObjects.Input(
                new ByteArrayInputStream(ProcessingFixture.PDF) {
                    public void close() throws IOException { throw new IOException("read completion failed"); }
                }, "application/pdf");
        var processor = new DocumentProcessor(failing, fixture, fixture, Clock.systemUTC(), "uploads", "reports", "1");
        assertThrows(IOException.class, () -> processor.process(fixture.request()));
        assertTrue(fixture.reports.isEmpty());
        assertTrue(fixture.published.isEmpty());
    }
}
