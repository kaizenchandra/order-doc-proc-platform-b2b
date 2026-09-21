package com.synechisveltiosi.platform.order.adapter.storage;

import com.google.auth.ServiceAccountSigner;
import com.google.cloud.storage.*;
import com.synechisveltiosi.platform.order.application.ApiFailure;
import com.synechisveltiosi.platform.order.application.DocumentStorage;
import com.synechisveltiosi.platform.order.domain.GcsObjectReference;
import com.synechisveltiosi.platform.order.domain.VerifiedUpload;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class GcsDocumentStorage implements DocumentStorage {
    private final Storage storage;
    private final ServiceAccountSigner signer;
    private final Clock clock;
    private final String uploadBucket;
    private final String reportBucket;

    public GcsDocumentStorage(Storage storage, ServiceAccountSigner signer, Clock clock, String uploadBucket, String reportBucket) {
        if (uploadBucket.isBlank() || reportBucket.isBlank() || uploadBucket.equals(reportBucket))
            throw new IllegalArgumentException("Distinct upload and report buckets are required");
        this.storage = storage;
        this.signer = signer;
        this.clock = clock;
        this.uploadBucket = uploadBucket;
        this.reportBucket = reportBucket;
    }

    @Override
    public UploadAuthorization authorize(String bucket, String name, String contentType, Instant expiresAt, long maxBytes) {
        requireBucket(bucket, uploadBucket);
        if (maxBytes < 1 || maxBytes > VerifiedUpload.MAX_SIZE_BYTES || contentType == null || contentType.isBlank())
            throw new IllegalArgumentException("Invalid upload bounds");
        long seconds = lifetime(expiresAt, 600);
        var headers = Map.of("Content-Type", contentType, "x-goog-if-generation-match", "0",
                "x-goog-content-length-range", "1," + maxBytes);
        try {
            var url = storage.signUrl(BlobInfo.newBuilder(bucket, name).setContentType(contentType).build(), seconds, TimeUnit.SECONDS,
                    Storage.SignUrlOption.withV4Signature(), Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
                    Storage.SignUrlOption.signWith(signer),
                    Storage.SignUrlOption.withExtHeaders(Map.of("content-type", contentType,
                            "x-goog-if-generation-match", "0", "x-goog-content-length-range", "1," + maxBytes)));
            URI uri = url.toURI();
            return new UploadAuthorization(uri, "PUT", headers, checkedExpiration(uri, expiresAt));
        } catch (Exception failure) {
            throw new ApiFailure(503, "Upload authorization is temporarily unavailable");
        }
    }

    @Override
    public VerifiedUpload inspect(String bucket, String name) {
        requireBucket(bucket, uploadBucket);
        try {
            var blob = storage.get(BlobId.of(bucket, name));
            if (blob == null) throw ApiFailure.conflict("Upload is not available");
            if (blob.getSize() == null || blob.getGeneration() == null || blob.getGeneration() <= 0
                    || !bucket.equals(blob.getBucket()) || !name.equals(blob.getName()))
                throw new ApiFailure(502, "Storage returned inconsistent metadata");
            if (blob.getSize() <= 0 || blob.getSize() > VerifiedUpload.MAX_SIZE_BYTES)
                throw new ApiFailure(422, "Uploaded document size is invalid");
            return new VerifiedUpload(new GcsObjectReference(bucket, name, blob.getGeneration()), blob.getSize());
        } catch (StorageException failure) {
            if (failure.getCode() == 404) throw ApiFailure.conflict("Upload is not available");
            throw new ApiFailure(503, "Upload inspection is temporarily unavailable");
        }
    }

    @Override
    public DownloadAuthorization authorizeDownload(GcsObjectReference object, Instant expiresAt) {
        requireBucket(object.bucket(), reportBucket);
        long seconds = lifetime(expiresAt, 300);
        try {
            var url = storage.signUrl(BlobInfo.newBuilder(object.bucket(), object.objectName()).build(), seconds, TimeUnit.SECONDS,
                    Storage.SignUrlOption.withV4Signature(), Storage.SignUrlOption.httpMethod(HttpMethod.GET),
                    Storage.SignUrlOption.signWith(signer), Storage.SignUrlOption.withQueryParams(Map.of(
                            "generation", Long.toString(object.generation()), "response-content-type", "application/json",
                            "response-content-disposition", "attachment; filename=report.json")));
            URI uri = url.toURI();
            return new DownloadAuthorization(uri, "GET", Map.of(), checkedExpiration(uri, expiresAt));
        } catch (Exception failure) {
            throw new ApiFailure(503, "Report authorization is temporarily unavailable");
        }
    }

    private long lifetime(Instant expiresAt, long limit) {
        long seconds = Math.min(limit, Duration.between(clock.instant(), expiresAt).getSeconds() - 1);
        if (seconds < 1) throw ApiFailure.conflict("Authorization window has expired");
        return seconds;
    }

    private Instant checkedExpiration(URI uri, Instant deadline) {
        var query = new HashMap<String, String>();
        for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        var formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
        Instant actual = Instant.from(formatter.parse(query.get("X-Goog-Date")))
                .plusSeconds(Long.parseLong(query.get("X-Goog-Expires")));
        // Signing may be remote. Never hand out a URL extending the registration deadline because clocks moved.
        if (actual.isAfter(deadline) || !actual.isAfter(clock.instant()))
            throw new IllegalStateException("Invalid signed expiration");
        return actual;
    }

    private void requireBucket(String actual, String expected) {
        if (!expected.equals(actual)) throw new IllegalArgumentException("Storage bucket is not allowed");
    }
}
