package com.synechisveltiosi.platform.document;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.synechisveltiosi.platform.commonobservability.TraceContext;
import com.synechisveltiosi.platform.document.application.ResultPublisher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DocumentPushIT {
    private static final String ISSUER = "https://issuer.test";
    private static final String AUDIENCE = "document-push";
    private static final String EMAIL = "push@example.iam.gserviceaccount.com";
    private static final String SUBSCRIPTION = "projects/test/subscriptions/document-requests";
    private static final ProcessingFixture fixture = new ProcessingFixture();
    private static final JsonMapper json = JsonMapper.builder().build();
    private static final AtomicReference<String> observedTrace = new AtomicReference<>();
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
            // Separate interface beans ensure the real processor is exercised with only external I/O replaced.
            context = new SpringApplicationBuilder(DocumentServiceApplication.class).initializers(ctx -> {
                ctx.getBeanFactory().registerSingleton("testObjects", (com.synechisveltiosi.platform.document.application.DocumentObjects) fixture::open);
                ctx.getBeanFactory().registerSingleton("testReports", new com.synechisveltiosi.platform.document.application.ReportStore() {
                    public Optional<Stored> find(String bucket, String path) {
                        return fixture.find(bucket, path);
                    }

                    public Stored createIfAbsent(String bucket, String path, com.synechisveltiosi.platform.document.domain.CanonicalReport report) throws java.io.IOException {
                        return fixture.createIfAbsent(bucket, path, report);
                    }
                });
                ctx.getBeanFactory().registerSingleton("testPublisher", (ResultPublisher) event -> {
                    observedTrace.set(TraceContext.current());
                    fixture.publish(event);
                });
            }).run("--server.port=0", "--server.address=127.0.0.1", "--app.push.issuer=" + ISSUER,
                    "--app.push.jwk-set-uri=http://127.0.0.1:" + keys.getAddress().getPort() + "/jwks",
                    "--app.push.audience=" + AUDIENCE, "--app.push.service-account-email=" + EMAIL,
                    "--app.push.subscription=" + SUBSCRIPTION, "--app.storage.upload-bucket=uploads", "--app.storage.report-bucket=reports");
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
        fixture.reset();
        observedTrace.set(null);
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
                "data", Base64.getEncoder().encodeToString(json.writeValueAsBytes(fixture.request())),
                "attributes", Map.of("traceparent", trace))));
    }

    private int post(String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + "/internal/pubsub/document-requests"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void validDeliveryAcknowledgesOnlyAfterPersistAndPublishAndPropagatesTrace() throws Exception {
        String trace = "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01";
        String body = body(trace);
        assertEquals(204, post(body, token()));
        assertEquals(trace, observedTrace.get());
        var first = fixture.published.getFirst();
        assertEquals(204, post(body, token()));
        assertEquals(first, fixture.published.getLast());
        assertEquals(1, fixture.opens.get());
        assertEquals(204, post(body("invalid"), token()));
        assertNull(observedTrace.get());
    }

    @Test
    void transientPublishFailureDoesNotAckAndRedeliveryUsesThePersistedReport() throws Exception {
        String body = body("invalid");
        fixture.failPublish = true;
        assertEquals(503, post(body, token()));
        assertEquals(1, fixture.reports.size());
        var id = fixture.reports.values().iterator().next().report().resultEventId();
        fixture.failPublish = false;
        assertEquals(204, post(body, token()));
        assertEquals(id, fixture.published.getFirst().eventId());
        assertEquals(1, fixture.opens.get());
    }

    @Test
    void invalidDocumentAcknowledgesADurableFailureResult() throws Exception {
        fixture.input = new byte[0];
        assertEquals(204, post(body("invalid"), token()));
        assertEquals("DocumentProcessingFailed", fixture.published.getFirst().eventType());
    }

    @Test
    void malformedWrongSubscriptionAndOversizedMessagesCannotAcknowledge() throws Exception {
        assertEquals(503, post("{}", token()));
        assertEquals(503, post(body("invalid").replace(SUBSCRIPTION, "projects/test/subscriptions/other"), token()));
        assertEquals(413, post(" ".repeat(96 * 1024 + 1), token()));
        assertTrue(fixture.reports.isEmpty());
        assertTrue(fixture.published.isEmpty());
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
        assertTrue(fixture.reports.isEmpty());
        assertEquals(0, fixture.opens.get());
    }
}
