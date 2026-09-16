package com.synechisveltiosi.platform.order.domain;

import java.time.Instant;
import java.util.Objects;

final class DomainChecks {
    private DomainChecks() {}
    static String text(String value, int max, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > max) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }
    static Instant time(Instant value, Instant earliest) {
        Objects.requireNonNull(value, "timestamp");
        if (value.isBefore(earliest)) throw new IllegalArgumentException("Timestamp precedes current state");
        return value;
    }
}
