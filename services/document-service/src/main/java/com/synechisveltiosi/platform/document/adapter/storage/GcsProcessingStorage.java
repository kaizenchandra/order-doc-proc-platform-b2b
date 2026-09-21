package com.synechisveltiosi.platform.document.adapter.storage;

import com.google.cloud.storage.*;
import com.synechisveltiosi.platform.document.application.DocumentObjects;
import com.synechisveltiosi.platform.document.application.ReportStore;
import com.synechisveltiosi.platform.document.domain.CanonicalReport;
import com.synechisveltiosi.platform.eventcontracts.Events;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.util.Optional;

public final class GcsProcessingStorage implements DocumentObjects, ReportStore {
    private final Storage storage;
    private final String uploadBucket;
    private final String reportBucket;
    private final CanonicalReportCodec codec = new CanonicalReportCodec();

    public GcsProcessingStorage(Storage storage, String uploadBucket, String reportBucket) {
        if (uploadBucket.isBlank() || reportBucket.isBlank() || uploadBucket.equals(reportBucket))
            throw new IllegalArgumentException("Distinct upload and report buckets are required");
        this.storage = storage;
        this.uploadBucket = uploadBucket;
        this.reportBucket = reportBucket;
    }

    @Override
    public Input open(String bucket, String name, String generation) throws IOException {
        requireBucket(bucket, uploadBucket);
        if (generation == null || !generation.matches("[1-9][0-9]{0,18}") || Long.parseLong(generation) <= 0)
            throw new IllegalArgumentException("Invalid generation");
        var id = BlobId.of(bucket, name, Long.parseLong(generation));
        try {
            var metadata = storage.get(id);
            if (metadata == null) throw new IOException("Input generation is unavailable");
            validateIdentity(metadata, bucket, name);
            if (!id.getGeneration().equals(metadata.getGeneration()))
                throw new IOException("Input generation mismatch");
            return new Input(read(id), metadata.getContentType());
        } catch (StorageException failure) {
            throw new IOException("Input storage is unavailable");
        }
    }

    @Override
    public Optional<Stored> find(String bucket, String name) throws IOException {
        requireBucket(bucket, reportBucket);
        try {
            var metadata = storage.get(BlobId.of(bucket, name));
            if (metadata == null) return Optional.empty();
            validateIdentity(metadata, bucket, name);
            if (metadata.getSize() == null || metadata.getSize() < 1 || metadata.getSize() > CanonicalReportCodec.MAX_BYTES)
                throw new IOException("Invalid canonical report size");
            // The metadata lookup may race a replacement. Always read the generation we just inspected.
            try (var input = read(BlobId.of(bucket, name, metadata.getGeneration()))) {
                byte[] bytes = input.readNBytes(CanonicalReportCodec.MAX_BYTES + 1);
                CanonicalReport report;
                try {
                    report = codec.decode(bytes);
                } catch (RuntimeException corrupt) {
                    throw new IOException("Invalid canonical report");
                }
                requirePath(name, report);
                return Optional.of(new Stored(report, metadata.getGeneration().toString()));
            }
        } catch (StorageException failure) {
            // Only an absent metadata lookup means no report. A missing pinned generation is a retryable race.
            throw new IOException("Report storage is unavailable");
        }
    }

    @Override
    public Stored createIfAbsent(String bucket, String name, CanonicalReport report) throws IOException {
        requireBucket(bucket, reportBucket);
        requirePath(name, report);
        byte[] bytes = codec.encode(report);
        try {
            var created = storage.create(BlobInfo.newBuilder(bucket, name).setContentType("application/json")
                    .setCacheControl("no-store").build(), bytes, Storage.BlobTargetOption.doesNotExist());
            validateIdentity(created, bucket, name);
            return new Stored(report, created.getGeneration().toString());
        } catch (StorageException failure) {
            if (failure.getCode() == 412)
                return find(bucket, name).orElseThrow(() -> new IOException("Canonical report winner is unavailable"));
            // Ambiguous failures propagate; redelivery performs find before any new creation attempt.
            throw new IOException("Report creation is unavailable");
        }
    }

    private InputStream read(BlobId id) {
        var channel = storage.reader(id, Storage.BlobSourceOption.generationMatch(id.getGeneration()),
                Storage.BlobSourceOption.shouldReturnRawInputStream(true));
        channel.setChunkSize(64 * 1024);
        return Channels.newInputStream(channel);
    }

    private void validateIdentity(Blob blob, String bucket, String name) throws IOException {
        if (blob == null || !bucket.equals(blob.getBucket()) || !name.equals(blob.getName())
                || blob.getGeneration() == null || blob.getGeneration() <= 0)
            throw new IOException("Inconsistent object metadata");
    }

    private void requireBucket(String actual, String expected) {
        if (!expected.equals(actual)) throw new IllegalArgumentException("Storage bucket is not allowed");
    }

    private void requirePath(String name, CanonicalReport report) throws IOException {
        var request = (Events.DocumentProcessingRequested) report.request().data();
        if (!Events.reportObject(report.request().tenantId(), request.processingRequestId()).equals(name))
            throw new IOException("Canonical report path mismatch");
    }
}
