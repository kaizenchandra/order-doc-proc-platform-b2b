package com.synechisveltiosi.platform.order.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "order_documents")
public class OrderDocument {
    @Id
    private UUID id;
    @Column(nullable = false, updatable = false)
    private UUID tenantId;
    // Scalar reference avoids loading an unbounded order/document object graph.
    // Flyway enforces the composite (tenant_id, order_id) foreign key.
    @Column(nullable = false, updatable = false)
    private UUID orderId;
    @Column(nullable = false, length = 255, updatable = false)
    private String fileName;
    @Column(nullable = false, length = 100, updatable = false)
    private String declaredContentType;
    @Column(nullable = false, length = 222, updatable = false)
    private String bucket;
    @Column(nullable = false, length = 512, updatable = false)
    private String objectName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DocumentStatus status;
    @Column(nullable = false, updatable = false)
    private Instant uploadExpiresAt;
    private Long objectGeneration;
    private Long sizeBytes;
    private UUID processingRequestId;
    @Column(length = 32)
    private String processorVersion;
    private Instant queuedAt;
    private Instant completedAt;
    @Column(length = 222)
    private String reportBucket;
    @Column(length = 512)
    private String reportObjectName;
    private Long reportGeneration;
    @Column(length = 64)
    private String sha256;
    @Column(length = 64)
    private String failureCode;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    @Version
    private Long version;

    protected OrderDocument() {
    }

    public static OrderDocument register(UUID id, UUID tenantId, UUID orderId, String fileName,
                                         String contentType, String bucket, String objectName, Instant expiresAt, Instant now) {
        Objects.requireNonNull(now);
        if (!Objects.requireNonNull(expiresAt).isAfter(now))
            throw new IllegalArgumentException("Upload window must be positive");
        var document = new OrderDocument();
        document.id = Objects.requireNonNull(id);
        document.tenantId = Objects.requireNonNull(tenantId);
        document.orderId = Objects.requireNonNull(orderId);
        document.fileName = DomainChecks.text(fileName, 255, "fileName");
        document.declaredContentType = DomainChecks.text(contentType, 100, "contentType");
        document.bucket = DomainChecks.text(bucket, 222, "bucket");
        document.objectName = DomainChecks.text(objectName, 512, "objectName");
        document.status = DocumentStatus.AWAITING_UPLOAD;
        document.uploadExpiresAt = expiresAt;
        document.createdAt = now;
        document.updatedAt = now;
        return document;
    }

    public void queue(VerifiedUpload upload, UUID requestId, String processor, Instant now) {
        requireStatus(DocumentStatus.AWAITING_UPLOAD);
        Objects.requireNonNull(upload);
        DomainChecks.time(now, updatedAt);
        if (!now.isBefore(uploadExpiresAt)) throw new IllegalStateException("Upload registration expired");
        if (!bucket.equals(upload.object().bucket()) || !objectName.equals(upload.object().objectName()))
            throw new IllegalArgumentException("Upload does not belong to this registration");
        Objects.requireNonNull(requestId);
        DomainChecks.text(processor, 32, "processorVersion");
        objectGeneration = upload.object().generation();
        sizeBytes = upload.sizeBytes();
        beginAttempt(requestId, processor, now);
    }

    public void reprocess(UUID newRequestId, String processor, Instant now) {
        requireStatus(DocumentStatus.FAILED);
        Objects.requireNonNull(newRequestId);
        if (newRequestId.equals(processingRequestId))
            throw new IllegalArgumentException("Reprocessing requires a new request ID");
        DomainChecks.text(processor, 32, "processorVersion");
        DomainChecks.time(now, updatedAt);
        beginAttempt(newRequestId, processor, now);
    }

    private void beginAttempt(UUID requestId, String processor, Instant now) {
        processingRequestId = requestId;
        processorVersion = processor;
        queuedAt = now;
        completedAt = null;
        reportBucket = null;
        reportObjectName = null;
        reportGeneration = null;
        sha256 = null;
        failureCode = null;
        status = DocumentStatus.QUEUED;
        updatedAt = now;
    }

    public boolean succeed(UUID requestId, GcsObjectReference report, String checksum, Instant now) {
        if (!accepts(requestId)) return false;
        Objects.requireNonNull(report);
        if (checksum == null || !checksum.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid SHA-256");
        DomainChecks.time(now, updatedAt);
        recordReport(report, now);
        sha256 = checksum;
        status = DocumentStatus.PROCESSED;
        return true;
    }

    public boolean fail(UUID requestId, GcsObjectReference report, String code, Instant now) {
        if (!accepts(requestId)) return false;
        Objects.requireNonNull(report);
        DomainChecks.text(code, 64, "failureCode");
        DomainChecks.time(now, updatedAt);
        recordReport(report, now);
        failureCode = code;
        status = DocumentStatus.FAILED;
        return true;
    }

    private boolean accepts(UUID requestId) {
        Objects.requireNonNull(requestId);
        // Late results cannot replace a newer attempt or an already terminal result.
        return status == DocumentStatus.QUEUED && requestId.equals(processingRequestId);
    }

    private void recordReport(GcsObjectReference report, Instant now) {
        reportBucket = report.bucket();
        reportObjectName = report.objectName();
        reportGeneration = report.generation();
        completedAt = now;
        updatedAt = now;
    }

    public void expire(Instant now) {
        requireStatus(DocumentStatus.AWAITING_UPLOAD);
        DomainChecks.time(now, updatedAt);
        if (now.isBefore(uploadExpiresAt)) throw new IllegalStateException("Upload window is still open");
        status = DocumentStatus.EXPIRED;
        updatedAt = now;
    }

    private void requireStatus(DocumentStatus expected) {
        if (status != expected) throw new IllegalStateException("Expected " + expected + ", found " + status);
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID orderId() {
        return orderId;
    }

    public String fileName() {
        return fileName;
    }

    public String declaredContentType() {
        return declaredContentType;
    }

    public String bucket() {
        return bucket;
    }

    public String objectName() {
        return objectName;
    }

    public DocumentStatus status() {
        return status;
    }

    public Instant uploadExpiresAt() {
        return uploadExpiresAt;
    }

    public Long objectGeneration() {
        return objectGeneration;
    }

    public Long sizeBytes() {
        return sizeBytes;
    }

    public UUID processingRequestId() {
        return processingRequestId;
    }

    public String processorVersion() {
        return processorVersion;
    }

    public Instant queuedAt() {
        return queuedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public String reportBucket() {
        return reportBucket;
    }

    public String reportObjectName() {
        return reportObjectName;
    }

    public Long reportGeneration() {
        return reportGeneration;
    }

    public String sha256() {
        return sha256;
    }

    public String failureCode() {
        return failureCode;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Long version() {
        return version;
    }
}
