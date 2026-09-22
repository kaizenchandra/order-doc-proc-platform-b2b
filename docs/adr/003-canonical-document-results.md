# ADR 003 — Make the stored report the processing result authority

Status: Accepted (implemented baseline). Reviewed: 2026-09-22.

## Context and decision

Register a server-selected upload path, authorize a short-lived create-only upload, and explicitly complete registration
after inspecting storage. Queue the exact GCS object generation. The processor reads that generation, validates bounded
PDF markers/size, streams a checksum, and creates a canonical report keyed by tenant and processingRequestId.

A create-only write selects one winning report during duplicate/concurrent processing. That report retains the original
request, stable result event ID, timestamp, and outcome. Retry reads it first, verifies request identity, and republishes
the same result with its report generation. Publish must succeed before the push request is acknowledged. Result
application checks the current processingRequestId and processor version before changing document state.

## Alternatives and consequences

A fresh result event ID on each delivery defeats event deduplication. Overwriting a report allows retries to change the
canonical outcome. Adding a processor SQL database would add another commit boundary without making GCS and Pub/Sub
atomic. A single canonical object keeps the processor database-free and supports recovery after the input is gone.

Input/storage failures remain retryable; invalid document bytes produce a terminal failure report. A missing input with
no canonical report cannot be reconstructed by retry alone. Object versions and canonical reports are recovery state,
so deleting them independently of replay/inbox retention is unsafe. A processingRequestId identifies an attempt, not
just a document; an intentional new attempt needs a new ID and an authorized application workflow. That workflow is not
currently exposed. PDF marker validation is not malware scanning, OCR, or full structural PDF validation.

Revisit when processing becomes multi-stage, results require user edits, or long-running tasks need durable progress.
Do not replace the immutable result with a mutable “latest report” without a new consistency design.

## Evidence

[DocumentProcessor](../../services/document-service/src/main/java/com/synechisveltiosi/platform/document/application/DocumentProcessor.java),
[CanonicalReport](../../services/document-service/src/main/java/com/synechisveltiosi/platform/document/domain/CanonicalReport.java),
[processor tests](../../services/document-service/src/test/java/com/synechisveltiosi/platform/document/DocumentProcessorTest.java),
and [GCS integration tests](../../services/document-service/src/test/java/com/synechisveltiosi/platform/document/GcsProcessingStorageIT.java).
