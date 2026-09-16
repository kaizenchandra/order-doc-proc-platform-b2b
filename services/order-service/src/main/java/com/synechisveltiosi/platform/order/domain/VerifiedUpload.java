package com.synechisveltiosi.platform.order.domain;

import java.util.Objects;

/**
 * Created by the storage adapter after inspecting GCS, never trusted directly from an HTTP body.
 */
public record VerifiedUpload(GcsObjectReference object, long sizeBytes) {
    public static final long MAX_SIZE_BYTES = 25L * 1024 * 1024;

    public VerifiedUpload {
        Objects.requireNonNull(object);
        if (sizeBytes <= 0 || sizeBytes > MAX_SIZE_BYTES) throw new IllegalArgumentException("Invalid document size");
    }
}
