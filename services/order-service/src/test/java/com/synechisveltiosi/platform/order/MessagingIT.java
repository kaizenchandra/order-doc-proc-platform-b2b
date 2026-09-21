package com.synechisveltiosi.platform.order;

import com.synechisveltiosi.platform.eventcontracts.Events;
import com.synechisveltiosi.platform.order.adapter.messaging.EventCodec;
import com.synechisveltiosi.platform.order.adapter.messaging.EventPublisher;
import com.synechisveltiosi.platform.order.adapter.messaging.OutboxRelay;
import com.synechisveltiosi.platform.order.adapter.messaging.ResultReceiver;
import com.synechisveltiosi.platform.order.adapter.persistence.*;
import com.synechisveltiosi.platform.order.application.ApiFailure;
import com.synechisveltiosi.platform.order.application.DocumentResultHandler;
import com.synechisveltiosi.platform.order.config.MessagingProperties;
import com.synechisveltiosi.platform.order.domain.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MessagingIT {
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static PostgreSQLContainer database;
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static OrderRepository orders;
    private static OrderDocumentRepository documents;
    private static OrderJournal journal;
    private static OutboxStore outbox;
    private static EventCodec codec;
    private static DocumentResultHandler handler;
    private static ResultReceiver receiver;
    private static MessagingProperties settings;

    @BeforeAll
    static void start() {
        database = new PostgreSQLContainer("postgres:17.6").withDatabaseName("messaging")
                .withUsername("messaging_test").withPassword(UUID.randomUUID().toString());
        try {
            database.start();
            context = new SpringApplicationBuilder(OrderServiceApplication.class).web(WebApplicationType.NONE).run(
                    "--spring.datasource.url=" + database.getJdbcUrl(), "--spring.datasource.username=" + database.getUsername(),
                    "--spring.datasource.password=" + database.getPassword(), "--app.messaging.report-bucket=reports");
            jdbc = context.getBean(JdbcTemplate.class);
            tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            orders = context.getBean(OrderRepository.class);
            documents = context.getBean(OrderDocumentRepository.class);
            journal = context.getBean(OrderJournal.class);
            outbox = context.getBean(OutboxStore.class);
            codec = context.getBean(EventCodec.class);
            handler = context.getBean(DocumentResultHandler.class);
            receiver = context.getBean(ResultReceiver.class);
            settings = context.getBean(MessagingProperties.class);
        } catch (RuntimeException | Error error) {
            stop();
            throw error;
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

    @BeforeEach
    void clear() {
        jdbc.execute("truncate orders, order_documents, outbox_events, inbox_events, api_idempotency");
    }

    private OutboxEvent pending() {
        var event = new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "OrderCreated", 1,
                UUID.randomUUID(), null, NOW, "order-service", "order-events", "{}", null);
        String data = "{\"orderId\":\"" + event.aggregateId() + "\",\"customerId\":\"" + UUID.randomUUID()
                + "\",\"status\":\"CREATED\",\"totalAmount\":10.00,\"currency\":\"INR\"}";
        event = new OutboxEvent(event.eventId(), event.tenantId(), event.aggregateId(), "OrderCreated", 1, event.correlationId(), null,
                NOW, "order-service", "order-events", data, "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01");
        var saved = event;
        tx.executeWithoutResult(s -> journal.append(saved));
        return event;
    }

    private OutboxRelay relay(EventPublisher publisher) {
        return new OutboxRelay(outbox, codec, publisher, settings);
    }

    private void expire(UUID id) {
        jdbc.update("update outbox_events set lease_until = now() - interval '1 second' where event_id = ?", id);
    }

    private OrderDocument queued() {
        var order = Order.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PO-1", BigDecimal.TEN, "INR", NOW);
        var doc = OrderDocument.register(UUID.randomUUID(), order.tenantId(), order.id(), "a.pdf", "application/pdf", "uploads", "object", NOW.plusSeconds(600), NOW);
        doc.queue(new VerifiedUpload(new GcsObjectReference("uploads", "object", 42), 100), UUID.randomUUID(), "1", NOW);
        tx.executeWithoutResult(s -> {
            orders.save(order);
            documents.save(doc);
        });
        return doc;
    }

    private Events.Envelope result(OrderDocument doc, UUID request, boolean success) {
        return new Events.Envelope(UUID.randomUUID(), success ? "DocumentProcessed" : "DocumentProcessingFailed", 1,
                doc.tenantId(), doc.orderId(), UUID.randomUUID(), UUID.randomUUID(), NOW, "document-service",
                new Events.DocumentResult(doc.id(), request, "1", "reports", Events.reportObject(doc.tenantId(), request), "43",
                        success ? "a".repeat(64) : null, success ? null : "UNSUPPORTED_FORMAT"));
    }

    private OrderDocument reload(OrderDocument doc) {
        return documents.findByTenantIdAndOrderIdAndId(doc.tenantId(), doc.orderId(), doc.id()).orElseThrow();
    }

    private int inboxCount() {
        return jdbc.queryForObject("select count(*) from inbox_events", Integer.class);
    }

    @Test
    void queueSamplingTracksPendingAgeAndDoesNotTreatPublishedEventsAsBacklog() {
        var monitor = new com.synechisveltiosi.platform.order.config.QueueMonitor(jdbc.getDataSource());
        assertEquals(0, monitor.snapshot().outboxOldestSeconds());
        var event = pending();
        assertTrue(monitor.snapshot().outboxOldestSeconds() > 300);
        var lease = outbox.claim().orElseThrow();
        assertTrue(outbox.published(lease));
        assertEquals(0, monitor.snapshot().outboxOldestSeconds());
        assertEquals(0, monitor.snapshot().queuedOldestSeconds());
    }

    @Test
    void relayPublishesOutsideTransactionAndMarksOnlyAcceptedMessages() {
        var event = pending();
        var calls = new AtomicInteger();
        assertEquals(1, relay((destination, bytes, attributes) -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("order-events", destination);
            assertEquals(event.eventId(), codec.decode(bytes).eventId());
            assertEquals(event.traceparent(), attributes.get("traceparent"));
            assertNotNull(jdbc.queryForObject("select claim_token from outbox_events where event_id = ?", UUID.class, event.eventId()));
            calls.incrementAndGet();
        }).poll());
        assertEquals(1, calls.get());
        assertNotNull(jdbc.queryForObject("select published_at from outbox_events where event_id = ?", Instant.class, event.eventId()));
        assertTrue(outbox.claim().isEmpty());
    }

    @Test
    void failureBacksOffAndRetainsEventForRetry() {
        var event = pending();
        relay((destination, bytes, attributes) -> {
            throw new IllegalStateException("broker unavailable");
        }).poll();
        assertEquals("PUBLISH_FAILED", jdbc.queryForObject("select last_error_code from outbox_events where event_id = ?", String.class, event.eventId()));
        assertNull(jdbc.queryForObject("select published_at from outbox_events where event_id = ?", Instant.class, event.eventId()));
        assertTrue(outbox.claim().isEmpty());
        jdbc.update("update outbox_events set available_at = now() where event_id = ?", event.eventId());
        var lease = outbox.claim().orElseThrow();
        assertEquals(2, lease.attempt());
        assertEquals(event.eventId(), lease.eventId());
    }

    @Test
    void expiredLeaseCannotMarkOrRescheduleAnotherWorkersEvent() {
        pending();
        var old = outbox.claim().orElseThrow();
        expire(old.eventId());
        var replacement = outbox.claim().orElseThrow();
        assertNotEquals(old.token(), replacement.token());
        assertFalse(outbox.published(old));
        assertFalse(outbox.retry(old, 1, "PUBLISH_FAILED"));
        assertTrue(outbox.published(replacement));
    }

    @Test
    void concurrentWorkersClaimDisjointRows() throws Exception {
        for (int i = 0; i < 4; i++) pending();
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<UUID>>();
            for (int i = 0; i < 4; i++)
                futures.add(executor.submit(() -> {
                    assertTrue(gate.await(5, TimeUnit.SECONDS));
                    return outbox.claim().orElseThrow().eventId();
                }));
            gate.countDown();
            var ids = new HashSet<UUID>();
            for (var future : futures) ids.add(future.get(10, TimeUnit.SECONDS));
            assertEquals(4, ids.size());
            assertTrue(outbox.claim().isEmpty());
        }
    }

    @Test
    void crashAfterPublishReusesTheSameEnvelope() {
        pending();
        var abandoned = outbox.claim().orElseThrow();
        byte[] first = codec.encode(abandoned.envelope(codec));
        // Simulate accepted publication followed by process death before SQL marking.
        expire(abandoned.eventId());
        relay((destination, bytes, attributes) -> assertArrayEquals(first, bytes)).poll();
        assertEquals(2, jdbc.queryForObject("select attempt_count from outbox_events", Integer.class));
    }

    @Test
    void invalidOutboxPayloadIsRetainedWithoutCallingPublisher() {
        var event = pending();
        jdbc.update("update outbox_events set event_version = 2 where event_id = ?", event.eventId());
        relay((destination, bytes, attributes) -> fail("Invalid event must not reach transport")).poll();
        assertEquals("INVALID_EVENT", jdbc.queryForObject("select last_error_code from outbox_events", String.class));
        assertNull(jdbc.queryForObject("select published_at from outbox_events", Instant.class));
    }

    @Test
    void ackOccursAfterCommitAndLostAckRedeliveryDoesNotReapply() {
        var doc = queued();
        var event = result(doc, doc.processingRequestId(), true);
        assertThrows(IllegalStateException.class, () -> receiver.receive(codec.encode(event), new ResultReceiver.Acknowledgement() {
            public void ack() {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(DocumentStatus.PROCESSED, reload(doc).status());
                assertEquals(1, inboxCount());
                throw new IllegalStateException("connection lost during ACK");
            }

            public void nack() {
                fail("Must not NACK a committed result");
            }
        }));
        assertEquals(DocumentResultHandler.Outcome.DUPLICATE, handler.handle(event));
        assertEquals(1L, reload(doc).version());
        assertEquals(1, inboxCount());
    }

    @Test
    void databaseFailureNacksAndRollsBackInboxAndDocument() {
        var doc = queued();
        var event = result(doc, doc.processingRequestId(), true);
        var nacks = new AtomicInteger();
        jdbc.execute("alter table order_documents add constraint test_reject_result check (status <> 'PROCESSED')");
        try {
            receiver.receive(codec.encode(event), new ResultReceiver.Acknowledgement() {
                public void ack() {
                    fail("Must not ACK a failed transaction");
                }

                public void nack() {
                    nacks.incrementAndGet();
                }
            });
            assertEquals(1, nacks.get());
            assertEquals(0, inboxCount());
            assertEquals(DocumentStatus.QUEUED, reload(doc).status());
        } finally {
            jdbc.execute("alter table order_documents drop constraint test_reject_result");
        }
        assertEquals(DocumentResultHandler.Outcome.APPLIED, handler.handle(event));
        assertEquals(1, inboxCount());
    }

    @Test
    void concurrentDuplicateResultsCommitOneEffect() throws Exception {
        var doc = queued();
        var event = result(doc, doc.processingRequestId(), true);
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var futures = new ArrayList<Future<DocumentResultHandler.Outcome>>();
            for (int i = 0; i < 3; i++)
                futures.add(executor.submit(() -> {
                    gate.await();
                    return handler.handle(event);
                }));
            gate.countDown();
            int applied = 0;
            for (var future : futures)
                if (future.get(10, TimeUnit.SECONDS) == DocumentResultHandler.Outcome.APPLIED) applied++;
            assertEquals(1, applied);
            assertEquals(1, inboxCount());
            assertEquals(1L, reload(doc).version());
        }
    }

    @Test
    void staleResultCannotOverwriteReprocessingOrTerminalState() {
        var doc = queued();
        assertEquals(DocumentResultHandler.Outcome.APPLIED, handler.handle(result(doc, doc.processingRequestId(), false)));
        var next = UUID.randomUUID();
        tx.executeWithoutResult(s -> reload(doc).reprocess(next, "1", Instant.now()));
        assertEquals(DocumentResultHandler.Outcome.STALE, handler.handle(result(doc, doc.processingRequestId(), true)));
        assertEquals(DocumentStatus.QUEUED, reload(doc).status());
        assertEquals(DocumentResultHandler.Outcome.APPLIED, handler.handle(result(doc, next, true)));
        assertEquals(DocumentResultHandler.Outcome.STALE, handler.handle(result(doc, next, false)));
        assertEquals(DocumentStatus.PROCESSED, reload(doc).status());
        assertEquals(4, inboxCount());
    }

    @Test
    void interruptedPublicationRetainsEventAndStopsThePoll() {
        var event = pending();
        pending();
        try {
            assertEquals(1, relay((destination, bytes, attributes) -> {
                throw new InterruptedException("shutdown");
            }).poll());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertNull(jdbc.queryForObject("select published_at from outbox_events where event_id = ?", Instant.class, event.eventId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where attempt_count = 0", Integer.class));
    }

    @Test
    void malformedResultNacksWithoutCommittingAnInboxEntry() {
        var nacks = new AtomicInteger();
        receiver.receive("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), new ResultReceiver.Acknowledgement() {
            public void ack() {
                fail("Malformed result must not be acknowledged");
            }

            public void nack() {
                nacks.incrementAndGet();
            }
        });
        assertEquals(1, nacks.get());
        assertEquals(0, inboxCount());
    }

    @Test
    void wrongProcessorVersionRollsBackInboxAndAllowsCorrectedRedelivery() {
        var doc = queued();
        var good = result(doc, doc.processingRequestId(), true);
        var data = (Events.DocumentResult) good.data();
        var wrongVersion = new Events.Envelope(good.eventId(), good.eventType(), 1, good.tenantId(), good.aggregateId(),
                good.correlationId(), null, NOW, good.source(), new Events.DocumentResult(data.documentId(),
                data.processingRequestId(), "2", data.reportBucket(), data.reportObjectName(), data.reportGeneration(), data.sha256(), null));
        assertThrows(IllegalArgumentException.class, () -> handler.handle(wrongVersion));
        assertEquals(0, inboxCount());
        assertEquals(DocumentStatus.QUEUED, reload(doc).status());
        assertEquals(DocumentResultHandler.Outcome.APPLIED, handler.handle(good));
    }

    @Test
    void untrustedReportAndWrongTenantAreRejectedWithoutInboxCommit() {
        var doc = queued();
        var good = result(doc, doc.processingRequestId(), true);
        var data = (Events.DocumentResult) good.data();
        var wrongReport = new Events.Envelope(good.eventId(), good.eventType(), 1, good.tenantId(), good.aggregateId(), good.correlationId(), null, NOW, good.source(),
                new Events.DocumentResult(data.documentId(), data.processingRequestId(), "1", "other-bucket", data.reportObjectName(), "43", data.sha256(), null));
        assertThrows(IllegalArgumentException.class, () -> handler.handle(wrongReport));
        UUID stranger = UUID.randomUUID();
        var wrongTenant = new Events.Envelope(UUID.randomUUID(), good.eventType(), 1, stranger, good.aggregateId(), good.correlationId(), null, NOW, good.source(),
                new Events.DocumentResult(data.documentId(), data.processingRequestId(), "1", "reports", Events.reportObject(stranger, data.processingRequestId()), "43", data.sha256(), null));
        assertThrows(ApiFailure.class, () -> handler.handle(wrongTenant));
        assertEquals(0, inboxCount());
        assertEquals(DocumentStatus.QUEUED, reload(doc).status());
    }

    @Test
    void realBrokerRedeliversAfterRollbackAndFansOutToIndependentSubscription() throws Exception {
        try (var emulator = new org.testcontainers.containers.GenericContainer<>(
                "gcr.io/google.com/cloudsdktool/google-cloud-cli:548.0.0-emulators")
                .withExposedPorts(8085)
                .withCommand("gcloud", "beta", "emulators", "pubsub", "start", "--project=transport-test", "--host-port=0.0.0.0:8085", "--quiet")
                .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forListeningPort())
                .withStartupTimeout(java.time.Duration.ofSeconds(90))) {
            emulator.start();
            String endpoint = emulator.getHost() + ":" + emulator.getMappedPort(8085);
            var channel = io.grpc.ManagedChannelBuilder.forTarget(endpoint).usePlaintext().build();
            var provider = com.google.api.gax.rpc.FixedTransportChannelProvider.create(
                    com.google.api.gax.grpc.GrpcTransportChannel.create(channel));
            var credentials = com.google.api.gax.core.NoCredentialsProvider.create();
            var topicSettings = com.google.cloud.pubsub.v1.TopicAdminSettings.newBuilder()
                    .setTransportChannelProvider(provider).setCredentialsProvider(credentials).build();
            var subscriptionSettings = com.google.cloud.pubsub.v1.SubscriptionAdminSettings.newBuilder()
                    .setTransportChannelProvider(provider).setCredentialsProvider(credentials).build();
            try (var topics = com.google.cloud.pubsub.v1.TopicAdminClient.create(topicSettings);
                 var subscriptions = com.google.cloud.pubsub.v1.SubscriptionAdminClient.create(subscriptionSettings)) {
                var topic = com.google.pubsub.v1.TopicName.of("transport-test", "physical-requests");
                topics.createTopic(topic);
                topics.createTopic(com.google.pubsub.v1.TopicName.of("transport-test", "physical-orders"));
                for (String name : new String[]{"order-results", "audit-results"})
                    subscriptions.createSubscription(com.google.pubsub.v1.Subscription.newBuilder()
                            .setName("projects/transport-test/subscriptions/" + name).setTopic(topic.toString())
                            .setAckDeadlineSeconds(10).build());
                var transportSettings = new MessagingProperties(true, "transport-test", endpoint, "physical-orders",
                        "physical-requests", "order-results", "reports", 90, 20);
                var doc = queued();
                var event = result(doc, doc.processingRequestId(), true);
                var rejected = new CountDownLatch(1);
                var accepted = new CountDownLatch(1);
                var attempts = new AtomicInteger();
                var observing = new ResultReceiver(codec, handler) {
                    @Override
                    public void receive(byte[] bytes, java.util.Map<String, String> attributes, Acknowledgement acknowledgement) {
                        attempts.incrementAndGet();
                        super.receive(bytes, attributes, new Acknowledgement() {
                            public void ack() {
                                acknowledgement.ack();
                                accepted.countDown();
                            }

                            public void nack() {
                                acknowledgement.nack();
                                rejected.countDown();
                            }
                        });
                    }
                };
                jdbc.execute("alter table order_documents add constraint test_broker_failure check (status <> 'PROCESSED')");
                try (var transport = new com.synechisveltiosi.platform.order.adapter.messaging.PubSubTransport(transportSettings)) {
                    var subscriber = transport.subscriber(observing);
                    try {
                        subscriber.startAsync().awaitRunning(20, TimeUnit.SECONDS);
                        transport.publish("document-requests", codec.encode(event), java.util.Map.of("eventId", event.eventId().toString()));
                        assertTrue(rejected.await(20, TimeUnit.SECONDS), "Broker delivery must be NACKed after DB failure");
                        assertEquals(0, inboxCount());
                        assertEquals(DocumentStatus.QUEUED, reload(doc).status());
                        jdbc.execute("alter table order_documents drop constraint test_broker_failure");
                        assertTrue(accepted.await(30, TimeUnit.SECONDS), "Same broker message must recover without republishing");
                        assertTrue(attempts.get() >= 2);
                        assertEquals(DocumentStatus.PROCESSED, reload(doc).status());
                        assertEquals(1, inboxCount());
                        assertEquals(1L, reload(doc).version());
                        // The order subscriber's ACK cannot consume the independent audit copy.
                        var copy = subscriptions.pull(com.google.pubsub.v1.PullRequest.newBuilder()
                                .setSubscription("projects/transport-test/subscriptions/audit-results").setMaxMessages(1).build());
                        assertEquals(1, copy.getReceivedMessagesCount());
                        var message = copy.getReceivedMessages(0);
                        assertEquals(event, codec.decode(message.getMessage().getData().toByteArray()));
                        assertEquals(event.eventId().toString(), message.getMessage().getAttributesOrThrow("eventId"));
                        subscriptions.acknowledge("projects/transport-test/subscriptions/audit-results", java.util.List.of(message.getAckId()));
                    } finally {
                        subscriber.stopAsync().awaitTerminated(20, TimeUnit.SECONDS);
                    }
                } finally {
                    jdbc.execute("alter table order_documents drop constraint if exists test_broker_failure");
                }
            } finally {
                channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
            }
        }
    }
}
