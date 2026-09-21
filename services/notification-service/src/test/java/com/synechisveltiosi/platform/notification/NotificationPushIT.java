package com.synechisveltiosi.platform.notification;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.synechisveltiosi.platform.eventcontracts.Events;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NotificationPushIT {
    private static final String ISSUER = "https://issuer.test";
    private static final String AUDIENCE = "notification-push";
    private static final String EMAIL = "push@example.iam.gserviceaccount.com";
    private static final String SUBSCRIPTION = "projects/test/subscriptions/notification-orders";
    private static final String RESULTS = "projects/test/subscriptions/notification-results";
    private static final JsonMapper json = JsonMapper.builder().build();
    private static PostgreSQLContainer database;
    private static JdbcTemplate jdbc;
    private static ConfigurableApplicationContext context;
    private static HttpServer keys;
    private static RSAKey signingKey;
    private static HttpClient client;
    private static String base;

    @BeforeAll
    static void start() throws Exception {
        try {
            signingKey = new RSAKeyGenerator(2048).keyID("push-test").generate();
            byte[] jwks = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            keys = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            keys.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(jwks);
                }
            });
            keys.start();
            database = new PostgreSQLContainer("postgres:17.6").withDatabaseName("notifications")
                    .withUsername("notification_test").withPassword(UUID.randomUUID().toString());
            database.start();
            context = new SpringApplicationBuilder(NotificationServiceApplication.class).run(
                    "--spring.datasource.url=" + database.getJdbcUrl(),
                    "--spring.datasource.username=" + database.getUsername(),
                    "--spring.datasource.password=" + database.getPassword(),
                    "--server.port=0", "--server.address=127.0.0.1", "--app.push.issuer=" + ISSUER,
                    "--app.push.jwk-set-uri=http://127.0.0.1:" + keys.getAddress().getPort() + "/jwks",
                    "--app.push.audience=" + AUDIENCE, "--app.push.service-account-email=" + EMAIL,
                    "--app.push.subscription=" + SUBSCRIPTION + "," + RESULTS);
            jdbc = context.getBean(JdbcTemplate.class);
            base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        } catch (Exception | Error failure) {
            stop();
            throw failure;
        }
    }

    @AfterAll
    static void stop() {
        try {
            if (client != null) client.close();
        } finally {
            try {
                if (context != null) context.close();
            } finally {
                if (keys != null) keys.stop(0);
                if (database != null) database.close();
            }
        }
    }

    @Test
    void probesExposeOnlyStatusAndDoNotExposeActuator() throws Exception {
        for (String path : new String[]{"/livez", "/readyz"}) {
            var response = client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(json.readTree("{\"status\":\"UP\"}"), json.readTree(response.body()));
        }
        var response = client.send(HttpRequest.newBuilder(URI.create(base + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE notification_records, audit_records, inbox_events");
    }

    private String token(String issuer, String audience, String email, boolean verified, Instant expires, RSAKey key) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject("push-identity").audience(audience)
                .claim("email", email).claim("email_verified", verified).issueTime(Date.from(Instant.now().minusSeconds(1)))
                .expirationTime(Date.from(expires)).build();
        var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("push-test").build(), claims);
        signed.sign(new RSASSASigner(key));
        return signed.serialize();
    }

    private String token() throws Exception {
        return token(ISSUER, AUDIENCE, EMAIL, true, Instant.now().plusSeconds(300), signingKey);
    }

    private String body(String trace) {
        return json.writeValueAsString(Map.of("subscription", SUBSCRIPTION, "message", Map.of("messageId", "1",
                "data", Base64.getEncoder().encodeToString(json.writeValueAsBytes(event())),
                "attributes", Map.of("traceparent", trace))));
    }

    private int post(String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + "/internal/pubsub/events"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private Events.Envelope event() {
        UUID order = UUID.randomUUID();
        return new Events.Envelope(UUID.randomUUID(), "OrderCreated", 1, UUID.randomUUID(), order,
                UUID.randomUUID(), null, Instant.now(), "order-service",
                new Events.OrderCreated(order, UUID.randomUUID(), "CREATED", new java.math.BigDecimal("12.00"), "USD"));
    }

    @Test
    void duplicateAndConcurrentDeliveryCommitOneEffect() throws Exception {
        String body = body("invalid");
        String bearer = token();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 4)
                    .<java.util.concurrent.Callable<Integer>>mapToObj(i -> () -> post(body, bearer)).toList();
            for (var result : executor.invokeAll(tasks)) assertEquals(204, result.get());
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from inbox_events", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_records", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from notification_records where status = 'RECORDED'", Integer.class));
    }

    @Test
    void failedCommitDoesNotAcknowledgeAndRedeliverySucceeds() throws Exception {
        String body = body("invalid");
        jdbc.execute("ALTER TABLE notification_records ADD CONSTRAINT test_failure CHECK (status <> 'RECORDED')");
        try {
            assertEquals(503, post(body, token()));
            assertEquals(0, jdbc.queryForObject("select count(*) from inbox_events", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from audit_records", Integer.class));
        } finally {
            jdbc.execute("ALTER TABLE notification_records DROP CONSTRAINT test_failure");
        }
        assertEquals(204, post(body, token()));
        assertEquals(1, jdbc.queryForObject("select count(*) from notification_records", Integer.class));
    }

    @Test
    void malformedWrongSubscriptionAndOversizedMessagesCannotAcknowledge() throws Exception {
        assertEquals(503, post("{}", token()));
        assertEquals(503, post(body("invalid").replace(SUBSCRIPTION, "projects/test/subscriptions/other"), token()));
        assertEquals(413, post(" ".repeat(96 * 1024 + 1), token()));
        assertEquals(0, jdbc.queryForObject("select count(*) from inbox_events", Integer.class));
    }

    @Test
    void authenticationChecksSignatureIssuerAudienceExpiryAndPushIdentity() throws Exception {
        String body = body("invalid");
        assertEquals(401, post(body, null));
        Instant future = Instant.now().plusSeconds(300);
        for (String invalid : new String[]{token("https://wrong.test", AUDIENCE, EMAIL, true, future, signingKey),
                token(ISSUER, "other", EMAIL, true, future, signingKey), token(ISSUER, AUDIENCE, "other@test", true, future, signingKey),
                token(ISSUER, AUDIENCE, EMAIL, false, future, signingKey),
                token(ISSUER, AUDIENCE, EMAIL, true, Instant.now().minusSeconds(300), signingKey),
                token(ISSUER, AUDIENCE, EMAIL, true, future, new RSAKeyGenerator(2048).generate())})
            assertEquals(401, post(body, invalid));
        assertEquals(0, jdbc.queryForObject("select count(*) from inbox_events", Integer.class));
    }

    private String delivery(Events.Envelope event, String subscription, String messageId) {
        return json.writeValueAsString(Map.of("subscription", subscription, "message", Map.of("messageId", messageId,
                "data", Base64.getEncoder().encodeToString(json.writeValueAsBytes(event)))));
    }

    @Test
    void independentlyAuditsOutOfOrderEventsWithoutRetainingPrivatePayloads() throws Exception {
        var created = event();
        var status = new Events.Envelope(UUID.randomUUID(), "OrderStatusChanged", 1, created.tenantId(), created.aggregateId(),
                created.correlationId(), created.eventId(), Instant.now(), "order-service",
                new Events.OrderStatusChanged(created.aggregateId(), "CREATED", "CONFIRMED"));
        UUID document = UUID.randomUUID(), request = UUID.randomUUID();
        var success = new Events.Envelope(UUID.randomUUID(), "DocumentProcessed", 1, created.tenantId(), created.aggregateId(),
                created.correlationId(), null, Instant.now(), "document-service", new Events.DocumentResult(document, request,
                "1", "private-reports", "sensitive-object-name", "42", "a".repeat(64), null));
        var failed = new Events.Envelope(UUID.randomUUID(), "DocumentProcessingFailed", 1, created.tenantId(), created.aggregateId(),
                created.correlationId(), null, Instant.now(), "document-service", new Events.DocumentResult(UUID.randomUUID(), UUID.randomUUID(),
                "1", "private-reports", "sensitive-object-name", "43", null, "INVALID_PDF"));
        for (var event : List.of(success, status, failed, created)) {
            String subscription = event.source().equals("order-service") ? SUBSCRIPTION : RESULTS;
            assertEquals(204, post(delivery(event, subscription, "same-transport-id"), token()));
            assertEquals(204, post(delivery(event, subscription, UUID.randomUUID().toString()), token()));
            var row = jdbc.queryForMap("select tenant_id, aggregate_id, correlation_id, summary::text as summary from audit_records where event_id = ?", event.eventId());
            assertEquals(event.tenantId(), row.get("tenant_id"));
            assertEquals(event.aggregateId(), row.get("aggregate_id"));
            assertEquals(event.correlationId(), row.get("correlation_id"));
            String summary = (String) row.get("summary");
            assertFalse(summary.contains("private-reports"));
            assertFalse(summary.contains("sensitive-object-name"));
            assertFalse(summary.contains("customerId"));
        }
        assertEquals(4, jdbc.queryForObject("select count(*) from inbox_events", Integer.class));
        assertEquals(4, jdbc.queryForObject("select count(*) from notification_records", Integer.class));
        assertEquals("FAILED", jdbc.queryForObject("select summary->>'outcome' from audit_records where event_id = ?", String.class, failed.eventId()));
        assertEquals(document.toString(), jdbc.queryForObject("select summary->>'documentId' from audit_records where event_id = ?", String.class, success.eventId()));
    }

    @Test
    void unsupportedVersionLeavesEventIdentityAvailableForCorrectedRedelivery() throws Exception {
        var event = event();
        String valid = delivery(event, SUBSCRIPTION, "transport-id");
        String unsupported = json.writeValueAsString(Map.of("subscription", SUBSCRIPTION, "message", Map.of("data",
                Base64.getEncoder().encodeToString(json.writeValueAsString(event).replace("\"eventVersion\":1", "\"eventVersion\":2").getBytes(StandardCharsets.UTF_8)))));
        assertEquals(503, post(unsupported, token()));
        assertEquals(0, jdbc.queryForObject("select count(*) from inbox_events", Integer.class));
        assertEquals(204, post(valid, token()));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_records", Integer.class));
    }

    @Test
    void chunkedOversizedBodyCannotBypassRequestLimit() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/internal/pubsub/events"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token())
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(new byte[96 * 1024 + 1])))
                .build();
        assertEquals(413, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(0, jdbc.queryForObject("select count(*) from inbox_events", Integer.class));
    }
}
