package com.synechisveltiosi.platform.order.adapter.persistence;

import com.synechisveltiosi.platform.eventcontracts.Events;
import com.synechisveltiosi.platform.order.adapter.messaging.EventCodec;
import com.synechisveltiosi.platform.order.config.MessagingProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
public class OutboxStore {
    private final JdbcTemplate jdbc;
    private final MessagingProperties settings;
    public OutboxStore(JdbcTemplate jdbc, MessagingProperties settings) { this.jdbc = jdbc; this.settings = settings; }
    public record Lease(UUID eventId, UUID token, int attempt, String destination, String eventType, int eventVersion,
            UUID tenantId, UUID aggregateId, UUID correlationId, UUID causationId, Instant occurredAt,
            String source, String data, String traceparent) {
        public Events.Envelope envelope(EventCodec codec) {
            return new Events.Envelope(eventId, eventType, eventVersion, tenantId, aggregateId, correlationId,
                    causationId, occurredAt, source, codec.payload(eventType, data));
        }
    }
    public Optional<Lease> claim() {
        var rows = jdbc.query("""
                WITH candidate AS (
                    SELECT event_id FROM outbox_events
                    WHERE published_at IS NULL AND available_at <= clock_timestamp()
                      AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                    ORDER BY available_at, occurred_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE outbox_events e SET claim_token = ?, lease_until = clock_timestamp() + (? * interval '1 second'),
                    attempt_count = least(e.attempt_count::bigint + 1, 2147483647)::integer
                FROM candidate c WHERE e.event_id = c.event_id RETURNING e.*
                """, (rs, index) -> new Lease(rs.getObject("event_id", UUID.class), rs.getObject("claim_token", UUID.class),
                rs.getInt("attempt_count"), rs.getString("destination"), rs.getString("event_type"), rs.getInt("event_version"),
                rs.getObject("tenant_id", UUID.class), rs.getObject("aggregate_id", UUID.class), rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(), rs.getString("source"),
                rs.getString("data"), rs.getString("traceparent")), UUID.randomUUID(), settings.leaseSeconds());
        return rows.stream().findFirst();
    }
    public boolean published(Lease lease) {
        return jdbc.update("""
                UPDATE outbox_events SET published_at = clock_timestamp(), claim_token = NULL, lease_until = NULL,
                    last_error_code = NULL WHERE event_id = ? AND claim_token = ? AND published_at IS NULL
                    AND lease_until > clock_timestamp()
                """, lease.eventId(), lease.token()) == 1;
    }
    public boolean retry(Lease lease, int delaySeconds, String errorCode) {
        if (delaySeconds < 1 || delaySeconds > 300 || !errorCode.matches("[A-Z_]{1,100}")) throw new IllegalArgumentException("Invalid retry");
        return jdbc.update("""
                UPDATE outbox_events SET available_at = clock_timestamp() + (? * interval '1 second'),
                    claim_token = NULL, lease_until = NULL, last_error_code = ?
                WHERE event_id = ? AND claim_token = ? AND published_at IS NULL AND lease_until > clock_timestamp()
                """, delaySeconds, errorCode, lease.eventId(), lease.token()) == 1;
    }
}
