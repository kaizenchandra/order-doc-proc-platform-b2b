package com.synechisveltiosi.platform.order.config;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** Bounded-time, read-only samples; never repair, republish, or delete business state. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true")
public class QueueMonitor {
    private final JdbcTemplate jdbc;

    public QueueMonitor(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(2);
    }

    public Snapshot snapshot() {
        double outbox = age("select coalesce(extract(epoch from (clock_timestamp() - min(occurred_at))), 0) from outbox_events where published_at is null");
        double queued = age("select coalesce(extract(epoch from (clock_timestamp() - min(queued_at))), 0) from order_documents where status = 'QUEUED'");
        return new Snapshot(outbox, queued);
    }

    private double age(String sql) {
        return Math.max(0, jdbc.queryForObject(sql, Double.class));
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void sample() {
        var log = LoggerFactory.getLogger(QueueMonitor.class);
        try {
            var value = snapshot();
            log.atInfo().addKeyValue("event", "queue_sample")
                    .addKeyValue("outbox_oldest_seconds", value.outboxOldestSeconds())
                    .addKeyValue("queued_oldest_seconds", value.queuedOldestSeconds()).log("Queue ages sampled");
        } catch (RuntimeException unavailable) {
            // Missing data is not a healthy zero, and monitoring must not stop the application.
            log.atWarn().addKeyValue("event", "queue_sample_failed").log("Queue sampling unavailable");
        }
    }

    public record Snapshot(double outboxOldestSeconds, double queuedOldestSeconds) {}
}
