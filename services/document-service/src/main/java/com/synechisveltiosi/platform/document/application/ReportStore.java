package com.synechisveltiosi.platform.document.application;

import com.synechisveltiosi.platform.document.domain.CanonicalReport;

import java.io.IOException;
import java.util.Optional;

public interface ReportStore {
    Optional<Stored> find(String bucket, String objectName) throws IOException;

    /** Atomically create if absent, or return the existing winner with its actual object generation. */
    Stored createIfAbsent(String bucket, String objectName, CanonicalReport report) throws IOException;

    record Stored(CanonicalReport report, String generation) {
        public Stored {
            java.util.Objects.requireNonNull(report);
            if (generation == null || !generation.matches("[1-9][0-9]{0,18}") || Long.parseLong(generation) <= 0)
                throw new IllegalArgumentException("Invalid report generation");
        }
    }
}
