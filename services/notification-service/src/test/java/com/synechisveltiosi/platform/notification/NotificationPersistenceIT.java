package com.synechisveltiosi.platform.notification;

import com.synechisveltiosi.platform.notification.adapter.persistence.AuditJournal;
import com.synechisveltiosi.platform.notification.adapter.persistence.AuditRecord;
import com.synechisveltiosi.platform.notification.adapter.persistence.InboxStore;
import com.synechisveltiosi.platform.notification.adapter.persistence.NotificationRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NotificationPersistenceIT {
    private static PostgreSQLContainer database;
    private static ConfigurableApplicationContext context;
    private static TransactionTemplate tx;
    private static InboxStore inbox;
    private static AuditJournal journal;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        database = new PostgreSQLContainer("postgres:17.6")
                .withDatabaseName("notifications").withUsername("notification_test").withPassword(UUID.randomUUID().toString());
        try {
            database.start();
            context = new SpringApplicationBuilder(NotificationServiceApplication.class).web(WebApplicationType.NONE).run(
                    "--spring.datasource.url=" + database.getJdbcUrl(),
                    "--spring.datasource.username=" + database.getUsername(),
                    "--spring.datasource.password=" + database.getPassword());
            tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            inbox = context.getBean(InboxStore.class);
            journal = context.getBean(AuditJournal.class);
            jdbc = context.getBean(JdbcTemplate.class);
        } catch (RuntimeException | Error failure) {
            stop();
            throw failure;
        }
    }

    @AfterAll
    static void stop() {
        try {
            if (context != null) context.close();
        } finally {
            if (database != null) database.close();
        }
    }

    private void record(UUID event) {
        record(event, UUID.randomUUID());
    }

    private void record(UUID event, UUID notificationId) {
        if (!inbox.claim("notification-domain", event)) return;
        UUID auditId = UUID.randomUUID();
        Instant now = Instant.now();
        journal.append(new AuditRecord(auditId, "notification-domain", event, UUID.randomUUID(), UUID.randomUUID(),
                        "DocumentProcessed", 1, UUID.randomUUID(), now, now, "{\"outcome\":\"PROCESSED\"}"),
                new NotificationRecord(notificationId, auditId, now));
    }

    private int auditCount(UUID event) {
        return jdbc.queryForObject("select count(*) from audit_records where event_id = ?", Integer.class, event);
    }

    @Test
    void duplicateDeliveryProducesOneAuditAndOneNotificationRecord() {
        var event = UUID.randomUUID();
        tx.executeWithoutResult(status -> record(event));
        tx.executeWithoutResult(status -> record(event));
        assertEquals(1, auditCount(event));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from notification_records n join audit_records a on a.id = n.audit_record_id
                where a.event_id = ? and n.status = 'RECORDED'
                """, Integer.class, event));
        assertEquals("object", jdbc.queryForObject("select jsonb_typeof(summary) from audit_records where event_id = ?", String.class, event));
    }

    @Test
    void businessFailureRollsBackInboxAuditAndIntentTogether() {
        var event = UUID.randomUUID();
        var notificationId = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            record(event, notificationId);
            throw new IllegalStateException("simulated transaction failure");
        }));
        assertEquals(0, auditCount(event));
        assertEquals(0, jdbc.queryForObject("select count(*) from inbox_events where event_id = ?", Integer.class, event));
        assertEquals(0, jdbc.queryForObject("select count(*) from notification_records where id = ?", Integer.class, notificationId));
        tx.executeWithoutResult(status -> record(event));
        assertEquals(1, auditCount(event));
    }

    @Test
    void notificationConstraintFailureRollsBackAuditAndInboxAndAllowsRetry() {
        var notificationId = UUID.randomUUID();
        var originalEvent = UUID.randomUUID();
        tx.executeWithoutResult(status -> record(originalEvent, notificationId));
        var event = UUID.randomUUID();
        // The duplicate notification ID fails at flush/commit after the audit insert.
        assertThrows(DataIntegrityViolationException.class,
                () -> tx.executeWithoutResult(status -> record(event, notificationId)));
        assertEquals(0, auditCount(event));
        assertEquals(0, jdbc.queryForObject("select count(*) from inbox_events where event_id = ?", Integer.class, event));
        assertEquals(1, auditCount(originalEvent));
        tx.executeWithoutResult(status -> record(event));
        assertEquals(1, auditCount(event));
    }

    @Test
    void journalAndInboxRequireAnExistingTransaction() {
        var event = UUID.randomUUID();
        var auditId = UUID.randomUUID();
        var now = Instant.now();
        var audit = new AuditRecord(auditId, "notification-domain", event, UUID.randomUUID(), UUID.randomUUID(),
                "DocumentProcessed", 1, UUID.randomUUID(), now, now, "{}");
        assertThrows(IllegalTransactionStateException.class, () -> inbox.claim("notification-domain", event));
        assertThrows(IllegalTransactionStateException.class,
                () -> journal.append(audit, new NotificationRecord(UUID.randomUUID(), auditId, now)));
        assertEquals(0, auditCount(event));
        assertEquals(0, jdbc.queryForObject("select count(*) from inbox_events where event_id = ?", Integer.class, event));
    }

    @Test
    void databaseContainsOnlyNotificationOwnedTables() {
        assertNull(jdbc.queryForObject("select to_regclass('orders')::text", String.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from flyway_schema_history where success and version = '1'", Integer.class));
    }
}
