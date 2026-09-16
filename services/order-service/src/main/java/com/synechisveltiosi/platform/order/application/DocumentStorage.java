package com.synechisveltiosi.platform.order.application;

import com.synechisveltiosi.platform.order.domain.VerifiedUpload;
import java.net.URI;
import java.time.Instant;
import java.util.Map;

/** Phase 7 implements GCS signing and metadata inspection. Never accept metadata from a client. */
public interface DocumentStorage {
    // Must authorize a create-only upload for precisely this object, bounded by expiresAt and maxBytes.
    UploadAuthorization authorize(String bucket, String objectName, String contentType, Instant expiresAt, long maxBytes);
    // Must inspect the stored object and return its immutable generation and actual size.
    VerifiedUpload inspect(String bucket, String objectName);
    record UploadAuthorization(URI url, String method, Map<String, String> headers, Instant expiresAt) {}
}
