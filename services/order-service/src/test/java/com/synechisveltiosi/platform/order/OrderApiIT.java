package com.synechisveltiosi.platform.order;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.synechisveltiosi.platform.order.application.ApiFailure;
import com.synechisveltiosi.platform.order.application.DocumentStorage;
import com.synechisveltiosi.platform.order.domain.GcsObjectReference;
import com.synechisveltiosi.platform.order.domain.VerifiedUpload;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class OrderApiIT {
    private static final String ISSUER = "https://issuer.example.test";
    private static final String AUDIENCE = "order-api";
    private static final TestStorage storage = new TestStorage();
    private static PostgreSQLContainer database;
    private static ConfigurableApplicationContext context;
    private static HttpServer jwks;
    private static RSAKey signingKey;
    private static HttpClient client;
    private static ObjectMapper json;
    private static JdbcTemplate jdbc;
    private static String base;

    @BeforeAll
    static void start() throws Exception {
        try {
            signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
            byte[] keys = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            jwks.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, keys.length);
                try (var body = exchange.getResponseBody()) {
                    body.write(keys);
                }
            });
            jwks.start();
            database = new PostgreSQLContainer("postgres:17.6").withDatabaseName("order_api")
                    .withUsername("api_test").withPassword(UUID.randomUUID().toString());
            database.start();
            context = new SpringApplicationBuilder(OrderServiceApplication.class)
                    .initializers(ctx -> ctx.getBeanFactory().registerSingleton("testDocumentStorage", storage))
                    .run("--server.port=0", "--server.address=127.0.0.1",
                            "--spring.datasource.url=" + database.getJdbcUrl(),
                            "--spring.datasource.username=" + database.getUsername(),
                            "--spring.datasource.password=" + database.getPassword(),
                            "--app.security.issuer=" + ISSUER, "--app.security.audience=" + AUDIENCE,
                            "--app.security.jwk-set-uri=http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks",
                            "--app.storage.upload-bucket=test-uploads");
            base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
            json = context.getBean(ObjectMapper.class);
            jdbc = context.getBean(JdbcTemplate.class);
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        } catch (Exception | Error error) {
            stop();
            throw error;
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
                try {
                    if (database != null) database.close();
                } finally {
                    if (jwks != null) jwks.stop(0);
                }
            }
        }
    }

    private String token(UUID tenant) throws Exception {
        return token(tenant.toString(), ISSUER, AUDIENCE, "orders:read orders:write", Instant.now().plusSeconds(300), signingKey);
    }

    private String token(String tenant, String issuer, String audience, String scope, Instant expires, RSAKey key) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject("test-user").audience(audience)
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(expires)).claim("scope", scope);
        if (tenant != null) claims.claim("tenant_id", tenant);
        var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims.build());
        signed.sign(new RSASSASigner(key));
        return signed.serialize();
    }

    private HttpResponse<String> request(String method, String path, String token, String key, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        String payload = body instanceof String raw ? raw : body == null ? "" : json.writeValueAsString(body);
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> orderInput() {
        return Map.of("customerId", UUID.randomUUID(), "customerReference", "PO-HTTP", "totalAmount", "49.95", "currency", "INR");
    }

    private String create(String token) throws Exception {
        var response = request("POST", "/api/v1/orders", token, UUID.randomUUID().toString(), orderInput());
        assertEquals(201, response.statusCode(), response.body());
        return response.headers().firstValue("Location").orElseThrow();
    }

    private String register(String token, String order) throws Exception {
        var response = request("POST", order + "/documents", token, UUID.randomUUID().toString(), Map.of("fileName", "invoice.pdf", "contentType", "application/pdf"));
        assertEquals(201, response.statusCode(), response.body());
        assertEquals("PUT", json.readTree(response.body()).at("/upload/method").asText());
        return response.headers().firstValue("Location").orElseThrow();
    }

    private UUID id(String location) {
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private long events(String order) {
        return jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?", Long.class, id(order));
    }

    private void problem(int status, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"), response.body());
        assertEquals(status, json.readTree(response.body()).path("status").asInt());
    }

    @Test
    void creationReplaysItsOriginalResponseAndRejectsChangedInput() throws Exception {
        var tenant = UUID.randomUUID();
        var token = token(tenant);
        var key = UUID.randomUUID().toString();
        var body = orderInput();
        var first = request("POST", "/api/v1/orders", token, key, body);
        assertEquals(201, first.statusCode(), first.body());
        var location = first.headers().firstValue("Location").orElseThrow();
        var correlation = first.headers().firstValue("X-Correlation-ID").orElseThrow();
        assertEquals(UUID.fromString(correlation), jdbc.queryForObject("select correlation_id from outbox_events where aggregate_id = ?", UUID.class, id(location)));
        var changed = request("PATCH", location + "/status", token, null, Map.of("status", "CONFIRMED", "expectedVersion", 0));
        assertEquals(200, changed.statusCode(), changed.body());
        var retry = request("POST", "/api/v1/orders", token, key, body);
        assertEquals(201, retry.statusCode());
        assertEquals(json.readTree(first.body()), json.readTree(retry.body()));
        assertEquals(first.headers().firstValue("Location"), retry.headers().firstValue("Location"));
        var different = new HashMap<>(body);
        different.put("totalAmount", "50.00");
        problem(409, request("POST", "/api/v1/orders", token, key, different));
        assertEquals(2, events(location));
        assertEquals(1, jdbc.queryForObject("select count(*) from orders where tenant_id = ?", Integer.class, tenant));
        assertEquals(201, request("POST", "/api/v1/orders", token(UUID.randomUUID()), key, body).statusCode());
    }

    @Test
    void concurrentCreationHasOneOrderOutboxAndIdempotencyRecord() throws Exception {
        var tenant = UUID.randomUUID();
        var token = token(tenant);
        var key = UUID.randomUUID().toString();
        var body = orderInput();
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < 4; i++)
                futures.add(executor.submit(() -> {
                    assertTrue(gate.await(5, TimeUnit.SECONDS));
                    return request("POST", "/api/v1/orders", token, key, body);
                }));
            gate.countDown();
            var locations = new HashSet<String>();
            for (var future : futures) {
                var response = future.get(20, TimeUnit.SECONDS);
                assertEquals(201, response.statusCode(), response.body());
                locations.add(response.headers().firstValue("Location").orElseThrow());
            }
            assertEquals(1, locations.size());
            assertEquals(1, events(locations.iterator().next()));
            assertEquals(1, jdbc.queryForObject("select count(*) from orders where tenant_id = ?", Integer.class, tenant));
            assertEquals(1, jdbc.queryForObject("select count(*) from api_idempotency where tenant_id = ?", Integer.class, tenant));
        }
    }

    @Test
    void authenticationChecksSignatureIssuerAudienceExpiryTenantAndScope() throws Exception {
        var tenant = UUID.randomUUID();
        var path = create(token(tenant));
        problem(401, request("GET", path, null, null, null));
        for (String bad : List.of("invalid", token(null, ISSUER, AUDIENCE, "orders:read", Instant.now().plusSeconds(300), signingKey),
                token("invalid", ISSUER, AUDIENCE, "orders:read", Instant.now().plusSeconds(300), signingKey),
                token(tenant.toString(), "https://wrong.test", AUDIENCE, "orders:read", Instant.now().plusSeconds(300), signingKey),
                token(tenant.toString(), ISSUER, "wrong", "orders:read", Instant.now().plusSeconds(300), signingKey),
                token(tenant.toString(), ISSUER, AUDIENCE, "orders:read", Instant.now().minusSeconds(300), signingKey),
                token(tenant.toString(), ISSUER, AUDIENCE, "orders:read", Instant.now().plusSeconds(300), new RSAKeyGenerator(2048).generate()))) {
            problem(401, request("GET", path, bad, null, null));
        }
        String readOnly = token(tenant.toString(), ISSUER, AUDIENCE, "orders:read", Instant.now().plusSeconds(300), signingKey);
        assertEquals(200, request("GET", path, readOnly, null, null).statusCode());
        problem(403, request("PATCH", path + "/status", readOnly, null, Map.of("status", "CONFIRMED", "expectedVersion", 0)));
    }

    @Test
    void tenantAndParentIsolationApplyToEveryResourceOperation() throws Exception {
        var owner = token(UUID.randomUUID());
        var stranger = token(UUID.randomUUID());
        var order = create(owner);
        var doc = register(owner, order);
        problem(404, request("GET", order, stranger, null, null));
        problem(404, request("PATCH", order + "/status", stranger, null, Map.of("status", "CANCELLED", "expectedVersion", 0)));
        problem(404, request("POST", order + "/documents", stranger, UUID.randomUUID().toString(), Map.of("fileName", "a.pdf", "contentType", "application/pdf")));
        problem(404, request("GET", doc, stranger, null, null));
        problem(404, request("POST", doc + "/complete", stranger, null, null));
        var otherOrder = create(owner);
        problem(404, request("GET", otherOrder + "/documents/" + id(doc), owner, null, null));
        problem(404, request("POST", otherOrder + "/documents/" + id(doc) + "/complete", owner, null, null));
        assertEquals(1, events(order));
    }

    @Test
    void validationAndStatusConflictsDoNotWriteEvents() throws Exception {
        var tenant = UUID.randomUUID();
        var token = token(tenant);
        problem(400, request("POST", "/api/v1/orders", token, null, orderInput()));
        problem(400, request("POST", "/api/v1/orders", token, "bad key", orderInput()));
        problem(400, request("POST", "/api/v1/orders", token, "malformed", "{"));
        problem(400, request("POST", "/api/v1/orders", token, "missing", Map.of("currency", "INR")));
        var invalid = new HashMap<>(orderInput());
        invalid.put("totalAmount", "1.001");
        problem(400, request("POST", "/api/v1/orders", token, "precision", invalid));
        assertEquals(0, jdbc.queryForObject("select count(*) from orders where tenant_id = ?", Integer.class, tenant));
        var order = create(token);
        problem(409, request("PATCH", order + "/status", token, null, Map.of("status", "FULFILLED", "expectedVersion", 0)));
        assertEquals(200, request("PATCH", order + "/status", token, null, Map.of("status", "CONFIRMED", "expectedVersion", 0)).statusCode());
        problem(409, request("PATCH", order + "/status", token, null, Map.of("status", "CANCELLED", "expectedVersion", 0)));
        assertEquals(200, request("PATCH", order + "/status", token, null, Map.of("status", "CONFIRMED", "expectedVersion", 1)).statusCode());
        assertEquals(2, events(order));
    }

    @Test
    void verifiedCompletionIsRetrySafeAndPersistsStorageGeneration() throws Exception {
        var token = token(UUID.randomUUID());
        var order = create(token);
        var doc = register(token, order);
        problem(409, request("POST", doc + "/complete", token, null, null));
        assertEquals(1, events(order));
        String object = jdbc.queryForObject("select object_name from order_documents where id = ?", String.class, id(doc));
        storage.uploads.put(object, new VerifiedUpload(new GcsObjectReference("test-uploads", object, 42), 1024));
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                gate.await();
                return request("POST", doc + "/complete", token, null, null);
            });
            var second = executor.submit(() -> {
                gate.await();
                return request("POST", doc + "/complete", token, null, null);
            });
            gate.countDown();
            var a = first.get(20, TimeUnit.SECONDS);
            var b = second.get(20, TimeUnit.SECONDS);
            assertEquals(202, a.statusCode(), a.body());
            assertEquals(202, b.statusCode(), b.body());
            assertEquals(json.readTree(a.body()), json.readTree(b.body()));
            assertEquals("QUEUED", json.readTree(a.body()).path("status").asText());
        }
        assertEquals(2, events(order));
        assertEquals(42L, jdbc.queryForObject("select object_generation from order_documents where id = ?", Long.class, id(doc)));
        assertEquals("42", jdbc.queryForObject("select data->>'generation' from outbox_events where aggregate_id = ? and event_type = 'DocumentProcessingRequested'", String.class, id(order)));
        storage.uploads.remove(object); // A retry after commit does not require another storage read.
        assertEquals(202, request("POST", doc + "/complete", token, null, null).statusCode());
        assertEquals(2, events(order));
    }

    @Test
    void signingFailureCanRetryRegistrationWithoutCreatingAnotherDocument() throws Exception {
        var token = token(UUID.randomUUID());
        var order = create(token);
        var key = UUID.randomUUID().toString();
        var body = Map.of("fileName", "invoice.pdf", "contentType", "application/pdf");
        storage.signingUnavailable = true;
        try {
            problem(503, request("POST", order + "/documents", token, key, body));
        } finally {
            storage.signingUnavailable = false;
        }
        var retry = request("POST", order + "/documents", token, key, body);
        assertEquals(201, retry.statusCode(), retry.body());
        assertEquals(1, jdbc.queryForObject("select count(*) from order_documents where order_id = ?", Integer.class, id(order)));
        problem(409, request("POST", order + "/documents", token, key, Map.of("fileName", "different.pdf", "contentType", "application/pdf")));
        assertEquals(1, events(order));
    }

    @Test
    void cancelledOrdersAndExpiredUploadsCannotQueueWork() throws Exception {
        var token = token(UUID.randomUUID());
        var order = create(token);
        var doc = register(token, order);
        String object = jdbc.queryForObject("select object_name from order_documents where id = ?", String.class, id(doc));
        storage.uploads.put(object, new VerifiedUpload(new GcsObjectReference("test-uploads", object, 42), 100));
        assertEquals(200, request("PATCH", order + "/status", token, null, Map.of("status", "CANCELLED", "expectedVersion", 0)).statusCode());
        problem(409, request("POST", doc + "/complete", token, null, null));
        problem(409, request("POST", order + "/documents", token, UUID.randomUUID().toString(), Map.of("fileName", "x", "contentType", "application/pdf")));
        assertEquals(2, events(order));
        var openOrder = create(token);
        var expired = register(token, openOrder);
        jdbc.update("update order_documents set created_at = now() - interval '20 minutes', upload_expires_at = now() - interval '10 minutes' where id = ?", id(expired));
        problem(409, request("POST", expired + "/complete", token, null, null));
        assertEquals(1, events(openOrder));
    }

    @Test
    void outboxFailureRollsBackCreationAndReleasesTheRetryKey() throws Exception {
        var tenant = UUID.randomUUID();
        var token = token(tenant);
        var key = UUID.randomUUID().toString();
        var body = orderInput();
        // Test-only constraint injects a database failure after the order insert.
        jdbc.execute("alter table outbox_events add constraint test_reject_order check (tenant_id <> '" + tenant + "'::uuid)");
        try {
            problem(409, request("POST", "/api/v1/orders", token, key, body));
            assertEquals(0, jdbc.queryForObject("select count(*) from orders where tenant_id = ?", Integer.class, tenant));
            assertEquals(0, jdbc.queryForObject("select count(*) from outbox_events where tenant_id = ?", Integer.class, tenant));
            assertEquals(0, jdbc.queryForObject("select count(*) from api_idempotency where tenant_id = ?", Integer.class, tenant));
        } finally {
            jdbc.execute("alter table outbox_events drop constraint test_reject_order");
        }
        var retry = request("POST", "/api/v1/orders", token, key, body);
        assertEquals(201, retry.statusCode(), retry.body());
        assertEquals(1, events(retry.headers().firstValue("Location").orElseThrow()));
    }

    @Test
    void outboxFailureRollsBackQueueingAndAllowsCompletionRetry() throws Exception {
        var token = token(UUID.randomUUID());
        var order = create(token);
        var doc = register(token, order);
        String object = jdbc.queryForObject("select object_name from order_documents where id = ?", String.class, id(doc));
        storage.uploads.put(object, new VerifiedUpload(new GcsObjectReference("test-uploads", object, 42), 100));
        jdbc.execute("alter table outbox_events add constraint test_reject_document check (data->>'documentId' is distinct from '" + id(doc) + "')");
        try {
            problem(409, request("POST", doc + "/complete", token, null, null));
            assertEquals("AWAITING_UPLOAD", jdbc.queryForObject("select status from order_documents where id = ?", String.class, id(doc)));
            assertNull(jdbc.queryForObject("select processing_request_id from order_documents where id = ?", UUID.class, id(doc)));
            assertEquals(1, events(order));
        } finally {
            jdbc.execute("alter table outbox_events drop constraint test_reject_document");
        }
        assertEquals(202, request("POST", doc + "/complete", token, null, null).statusCode());
        assertEquals(2, events(order));
    }

    @Test
    void storageFailureAndMismatchedEvidenceLeaveRegistrationUnchanged() throws Exception {
        var token = token(UUID.randomUUID());
        var order = create(token);
        var doc = register(token, order);
        String object = jdbc.queryForObject("select object_name from order_documents where id = ?", String.class, id(doc));
        storage.inspectionUnavailable = true;
        try {
            problem(503, request("POST", doc + "/complete", token, null, null));
        } finally {
            storage.inspectionUnavailable = false;
        }
        storage.uploads.put(object, new VerifiedUpload(new GcsObjectReference("wrong-bucket", object, 42), 100));
        problem(502, request("POST", doc + "/complete", token, null, null));
        assertEquals("AWAITING_UPLOAD", jdbc.queryForObject("select status from order_documents where id = ?", String.class, id(doc)));
        assertNull(jdbc.queryForObject("select object_generation from order_documents where id = ?", Long.class, id(doc)));
        assertEquals(1, events(order));
    }

    private static class TestStorage implements DocumentStorage {
        final Map<String, VerifiedUpload> uploads = new ConcurrentHashMap<>();
        volatile boolean signingUnavailable;
        volatile boolean inspectionUnavailable;

        public UploadAuthorization authorize(String bucket, String name, String type, Instant expires, long maxBytes) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Signing must be outside SQL transactions");
            assertEquals(VerifiedUpload.MAX_SIZE_BYTES, maxBytes);
            if (signingUnavailable) throw new ApiFailure(503, "Document storage is temporarily unavailable");
            return new UploadAuthorization(URI.create("https://storage.example.test/" + bucket + "/" + name), "PUT", Map.of("Content-Type", type, "x-goog-if-generation-match", "0"), expires);
        }

        public VerifiedUpload inspect(String bucket, String name) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Inspection must be outside SQL transactions");
            if (inspectionUnavailable) throw new ApiFailure(503, "Document storage is temporarily unavailable");
            var result = uploads.get(name);
            if (result == null) throw ApiFailure.conflict("Upload is not present");
            return result;
        }
    }
}
