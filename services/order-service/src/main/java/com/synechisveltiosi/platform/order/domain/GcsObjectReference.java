package com.synechisveltiosi.platform.order.domain;

/**
 * Exact immutable generation; never a signed URL or a client-selected bucket.
 */
public record GcsObjectReference(String bucket, String objectName, long generation) {
    public GcsObjectReference {
        DomainChecks.text(bucket, 222, "bucket");
        DomainChecks.text(objectName, 512, "objectName");
        if (generation <= 0) throw new IllegalArgumentException("generation must be positive");
    }
}
