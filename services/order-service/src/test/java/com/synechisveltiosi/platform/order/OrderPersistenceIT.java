package com.synechisveltiosi.platform.order;

import com.synechisveltiosi.platform.order.adapter.persistence.*;
import com.synechisveltiosi.platform.order.domain.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OrderPersistenceIT {
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static PostgreSQLContainer database;
    private static ConfigurableApplicationContext context;
    private static TransactionTemplate tx;
    private static OrderRepository orders;
    private static OrderDocumentRepository documents;
    private static OrderJournal journal;
    private static InboxStore inbox;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        // Explicit lifecycle preserves JUnit 5 without Spring 7's JUnit 6 extension.
        database = new PostgreSQLContainer("postgres:17.6")
                .withDatabaseName("orders").withUsername("order_test").withPassword(UUID.randomUUID().toString());
        try {
            database.start();
            context = new SpringApplicationBuilder(OrderServiceApplication.class).web(WebApplicationType.NONE).run(
                    "--spring.datasource.url=" + database.getJdbcUrl(),
                    "--spring.datasource.username=" + database.getUsername(),
                    "--spring.datasource.password=" + database.getPassword());
            tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            orders = context.getBean(OrderRepository.class);
            documents = context.getBean(OrderDocumentRepository.class);
            journal = context.getBean(OrderJournal.class);
            inbox = context.getBean(InboxStore.class);
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

    private Order newOrder() {
        return Order.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PO-1", new BigDecimal("49.95"), "USD", NOW);
    }

    private OutboxEvent event(Order order, String data) {
        return new OutboxEvent(UUID.randomUUID(), order.tenantId(), order.id(), "OrderCreated", 1,
                UUID.randomUUID(), null, NOW, "order-service", "order-events", data, null);
    }

    private long eventCount(UUID aggregateId) {
        return jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?", Long.class, aggregateId);
    }

    @Test
    void migrationAndHibernateValidationRunBeforeRepositoryUse() {
        assertEquals(1, jdbc.queryForObject("select count(*) from flyway_schema_history where success and version = '1'", Integer.class));
        assertEquals("order-service", context.getEnvironment().getProperty("spring.application.name"));
    }

    @Test
    void orderOutboxAndIdempotencyCommitTogetherAndJsonStaysAnObject() {
        var order = newOrder();
        var key = new IdempotencyId(order.tenantId(), "POST:/api/v1/orders", UUID.randomUUID().toString());
        tx.executeWithoutResult(status -> {
            orders.save(order);
            journal.append(event(order, "{\"customerReference\":\"PO-1\"}"));
            journal.remember(new IdempotencyRecord(key, "a".repeat(64), order.id(), 201,
                    "{\"orderId\":\"" + order.id() + "\"}", NOW, NOW.plusSeconds(86400)));
        });
        assertTrue(orders.findByTenantIdAndId(order.tenantId(), order.id()).isPresent());
        assertTrue(orders.findByTenantIdAndId(UUID.randomUUID(), order.id()).isEmpty());
        assertEquals(1, eventCount(order.id()));
        assertEquals("object", jdbc.queryForObject("select jsonb_typeof(data) from outbox_events where aggregate_id = ?", String.class, order.id()));
        assertEquals(order.id(), journal.find(key).orElseThrow().resourceId());
    }

    @Test
    void invalidOutboxDataRollsBackTheOrder() {
        var order = newOrder();
        assertThrows(DataIntegrityViolationException.class, () -> tx.executeWithoutResult(status -> {
            orders.save(order);
            journal.append(event(order, "[]"));
        }));
        assertTrue(orders.findByTenantIdAndId(order.tenantId(), order.id()).isEmpty());
        assertEquals(0, eventCount(order.id()));
    }

    @Test
    void tenantForeignKeyAndDocumentStateAreEnforcedByPostgres() {
        var order = newOrder();
        tx.executeWithoutResult(status -> orders.save(order));
        var doc = OrderDocument.register(UUID.randomUUID(), UUID.randomUUID(), order.id(), "invoice.pdf",
                "application/pdf", "uploads", UUID.randomUUID().toString(), NOW.plusSeconds(600), NOW);
        assertThrows(DataIntegrityViolationException.class, () -> tx.executeWithoutResult(status -> documents.save(doc)));
        var valid = OrderDocument.register(UUID.randomUUID(), order.tenantId(), order.id(), "invoice.pdf",
                "application/pdf", "uploads", UUID.randomUUID().toString(), NOW.plusSeconds(600), NOW);
        tx.executeWithoutResult(status -> documents.save(valid));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update order_documents set status = 'QUEUED' where id = ?", valid.id()));
        assertEquals(DocumentStatus.AWAITING_UPLOAD,
                documents.findByTenantIdAndOrderIdAndId(order.tenantId(), order.id(), valid.id()).orElseThrow().status());
    }

    @Test
    void documentResultSurvivesJpaRoundTrip() {
        var order = newOrder();
        var doc = OrderDocument.register(UUID.randomUUID(), order.tenantId(), order.id(), "invoice.pdf",
                "application/pdf", "uploads", UUID.randomUUID().toString(), NOW.plusSeconds(600), NOW);
        var request = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            orders.save(order);
            documents.save(doc);
        });
        tx.executeWithoutResult(status -> {
            var managed = documents.findByTenantIdAndOrderIdAndId(order.tenantId(), order.id(), doc.id()).orElseThrow();
            managed.queue(new VerifiedUpload(new GcsObjectReference(managed.bucket(), managed.objectName(), 42), 100), request, "1", NOW);
        });
        tx.executeWithoutResult(status -> {
            var managed = documents.findByTenantIdAndOrderIdAndId(order.tenantId(), order.id(), doc.id()).orElseThrow();
            managed.succeed(request, new GcsObjectReference("reports", "report.json", 43), "f".repeat(64), NOW.plusSeconds(2));
        });
        var result = documents.findByTenantIdAndOrderIdAndId(order.tenantId(), order.id(), doc.id()).orElseThrow();
        assertEquals(DocumentStatus.PROCESSED, result.status());
        assertEquals(43L, result.reportGeneration());
        assertEquals("f".repeat(64), result.sha256());
        assertEquals(2L, result.version());
    }

    @Test
    void staleWriterCannotUndoAnotherCommittedTransition() {
        var order = newOrder();
        tx.executeWithoutResult(status -> orders.save(order));
        var first = orders.findByTenantIdAndId(order.tenantId(), order.id()).orElseThrow();
        var stale = orders.findByTenantIdAndId(order.tenantId(), order.id()).orElseThrow();
        first.transitionTo(OrderStatus.CONFIRMED, NOW);
        tx.executeWithoutResult(status -> orders.save(first));
        stale.transitionTo(OrderStatus.CANCELLED, NOW);
        assertThrows(OptimisticLockingFailureException.class, () -> tx.executeWithoutResult(status -> orders.save(stale)));
        assertEquals(OrderStatus.CONFIRMED, orders.findByTenantIdAndId(order.tenantId(), order.id()).orElseThrow().status());
    }

    @Test
    void inboxRollbackAllowsRedeliveryAndRollsBackBusinessState() {
        var eventId = UUID.randomUUID();
        var order = newOrder();
        tx.executeWithoutResult(status -> orders.save(order));
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            assertTrue(inbox.claim("order-results", eventId));
            orders.findByTenantIdAndId(order.tenantId(), order.id()).orElseThrow().transitionTo(OrderStatus.CONFIRMED, NOW);
            throw new IllegalStateException("simulated business failure");
        }));
        assertEquals(OrderStatus.CREATED, orders.findByTenantIdAndId(order.tenantId(), order.id()).orElseThrow().status());
        assertEquals(Boolean.TRUE, tx.execute(status -> inbox.claim("order-results", eventId)));
        assertEquals(Boolean.FALSE, tx.execute(status -> inbox.claim("order-results", eventId)));
        assertEquals(Boolean.TRUE, tx.execute(status -> inbox.claim("another-consumer", eventId)));
    }

    @Test
    void concurrentInboxClaimsHaveOneWinner() throws Exception {
        var eventId = UUID.randomUUID();
        var claimed = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tx.execute(status -> {
                boolean won = inbox.claim("concurrent", eventId);
                claimed.countDown();
                try {
                    if (!secondStarted.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("second worker missing");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return won;
            }));
            assertTrue(claimed.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> tx.execute(status -> {
                secondStarted.countDown();
                return inbox.claim("concurrent", eventId);
            }));
            assertTrue(first.get(10, TimeUnit.SECONDS));
            assertFalse(second.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void journalAndInboxCannotCommitInAnAccidentalIndependentTransaction() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> inbox.claim("order-results", UUID.randomUUID()));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> journal.append(event(newOrder(), "{}")));
    }

    @Test
    void idempotencyKeyIsUniquePerTenantAndOperation() {
        UUID tenant = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        var id = new IdempotencyId(tenant, "POST:/api/v1/orders", key);
        tx.executeWithoutResult(status -> journal.remember(new IdempotencyRecord(id, "b".repeat(64), UUID.randomUUID(), 201, "{}", NOW, NOW.plusSeconds(60))));
        assertThrows(DataIntegrityViolationException.class, () -> tx.executeWithoutResult(status ->
                journal.remember(new IdempotencyRecord(id, "c".repeat(64), UUID.randomUUID(), 201, "{}", NOW, NOW.plusSeconds(60)))));
        var other = new IdempotencyId(UUID.randomUUID(), "POST:/api/v1/orders", key);
        tx.executeWithoutResult(status -> journal.remember(new IdempotencyRecord(other, "d".repeat(64), UUID.randomUUID(), 201, "{}", NOW, NOW.plusSeconds(60))));
        assertEquals("b".repeat(64), journal.find(id).orElseThrow().requestHash());
    }
}
