package com.synechisveltiosi.platform.order.application;

import com.synechisveltiosi.platform.order.adapter.persistence.IdempotencyId;
import com.synechisveltiosi.platform.order.adapter.persistence.IdempotencyRecord;
import com.synechisveltiosi.platform.order.adapter.persistence.OrderJournal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

import static com.synechisveltiosi.platform.order.application.OrderModels.StoredResponse;

@Component
public class IdempotentRequests {
    private final JdbcTemplate jdbc;
    private final OrderJournal journal;
    private final ObjectMapper json;
    private final Clock clock;

    public IdempotentRequests(JdbcTemplate jdbc, OrderJournal journal, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.journal = journal;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StoredResponse execute(UUID tenant, String operation, String key, Object canonicalRequest, Supplier<StoredResponse> work) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new ApiFailure(400, "Idempotency-Key must contain 1–128 letters, digits, dots, underscores, colons, or hyphens");
        var id = new IdempotencyId(tenant, operation, key);
        String hash = hash(json.writeValueAsString(canonicalRequest));
        // Serialize absent-key races across replicas; the lock is released on commit or rollback.
        // Hash collisions only serialize unrelated requests; the full primary key still determines identity.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
                },
                json.writeValueAsString(new String[]{tenant.toString(), operation, key}));
        var previous = journal.find(id);
        if (previous.isPresent()) {
            var saved = previous.get();
            if (!saved.requestHash().equals(hash))
                throw ApiFailure.conflict("Idempotency-Key was already used with different input");
            // Retain replay protection until a separately designed retention job removes the row.
            return new StoredResponse(saved.resourceId(), saved.responseBody());
        }
        var response = work.get();
        var now = clock.instant();
        journal.remember(new IdempotencyRecord(id, hash, response.resourceId(), 201, response.body(), now, now.plusSeconds(86400)));
        return response;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
