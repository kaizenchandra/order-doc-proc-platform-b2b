package com.synechisveltiosi.platform.document;

import com.google.cloud.NoCredentials;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.sun.net.httpserver.HttpServer;
import com.synechisveltiosi.platform.document.adapter.storage.CanonicalReportCodec;
import com.synechisveltiosi.platform.document.adapter.storage.GcsProcessingStorage;
import com.synechisveltiosi.platform.document.domain.CanonicalReport;
import com.synechisveltiosi.platform.eventcontracts.Events;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the real Java SDK's HTTP requests against scripted GCS JSON API responses, not cloud IAM. */
class GcsProcessingStorageIT {
    private final CanonicalReportCodec codec = new CanonicalReportCodec();
    private final JsonMapper json = JsonMapper.builder().build();

    private CanonicalReport report() {
        return new CanonicalReport(1, new ProcessingFixture().request(), UUID.randomUUID(), Instant.now(), 30, "a".repeat(64), null);
    }

    private String path(CanonicalReport report) {
        return Events.reportObject(report.request().tenantId(), ((Events.DocumentProcessingRequested) report.request().data()).processingRequestId());
    }

    private byte[] metadata(String bucket, String name, String generation, int size) {
        return json.writeValueAsBytes(Map.of("kind", "storage#object", "bucket", bucket, "name", name,
                "generation", generation, "size", Integer.toString(size), "contentType", bucket.equals("uploads") ? "application/pdf" : "application/json"));
    }

    @Test
    void inputMetadataAndMediaReadBothPinTheRequestedGeneration() throws Exception {
        try (var stub = new GcsStub()) {
            stub.add(200, metadata("uploads", "input.pdf", "42", ProcessingFixture.PDF.length));
            stub.add(200, ProcessingFixture.PDF);
            try (var input = stub.adapter.open("uploads", "input.pdf", "42")) {
                assertEquals("application/pdf", input.contentType());
                assertArrayEquals(ProcessingFixture.PDF, input.bytes().readAllBytes());
            }
            assertEquals(2, stub.requests.size());
            assertEquals("42", stub.requests.get(0).query().get("generation"));
            assertEquals("42", stub.requests.get(1).query().get("generation"));
            assertEquals("42", stub.requests.get(1).query().get("ifGenerationMatch"));
            assertEquals("media", stub.requests.get(1).query().get("alt"));
        }
    }

    @Test
    void missingInputGenerationNeverFallsBackToLatest() throws Exception {
        try (var stub = new GcsStub()) {
            stub.add(404, "{\"error\":{\"code\":404,\"message\":\"missing\"}}".getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> stub.adapter.open("uploads", "input.pdf", "42"));
            assertEquals(1, stub.requests.size());
            assertEquals("42", stub.requests.getFirst().query().get("generation"));
        }
    }

    @Test
    void rawInputBytesAreNotTransparentlyDecompressed() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(ProcessingFixture.PDF); }
        try (var stub = new GcsStub()) {
            stub.add(200, metadata("uploads", "input.pdf", "42", bytes.size()));
            stub.replies.add(new Reply(200, bytes.toByteArray(), "gzip"));
            try (var input = stub.adapter.open("uploads", "input.pdf", "42")) {
                assertArrayEquals(bytes.toByteArray(), input.bytes().readAllBytes());
            }
        }
    }

    @Test
    void createUsesZeroGenerationPreconditionAndReturnsTheAssignedGeneration() throws Exception {
        var report = report();
        try (var stub = new GcsStub()) {
            stub.add(200, metadata("reports", path(report), "123", codec.encode(report).length));
            var stored = stub.adapter.createIfAbsent("reports", path(report), report);
            assertEquals(report, stored.report());
            assertEquals("123", stored.generation());
            assertEquals(1, stub.requests.size());
            var request = stub.requests.getFirst();
            assertEquals("POST", request.method());
            assertEquals("0", request.query().get("ifGenerationMatch"));
            assertTrue(request.body().contains(report.resultEventId().toString()));
            assertTrue(request.body().contains("application/json"));
        }
    }

    @Test
    void preconditionConflictReadsAndReturnsTheWinningReport() throws Exception {
        var winner = report();
        var loser = new CanonicalReport(1, winner.request(), UUID.randomUUID(), Instant.now(), 30, "b".repeat(64), null);
        try (var stub = new GcsStub()) {
            stub.add(412, "{\"error\":{\"code\":412,\"message\":\"exists\"}}".getBytes(StandardCharsets.UTF_8));
            stub.add(200, metadata("reports", path(winner), "987", codec.encode(winner).length));
            stub.add(200, codec.encode(winner));
            var stored = stub.adapter.createIfAbsent("reports", path(winner), loser);
            assertEquals(winner, stored.report());
            assertEquals("987", stored.generation());
            assertEquals("987", stub.requests.get(2).query().get("generation"));
            assertEquals("987", stub.requests.get(2).query().get("ifGenerationMatch"));
            assertEquals(1, stub.requests.stream().filter(r -> r.method().equals("POST")).count());
        }
    }

    @Test
    void oversizedCorruptAndWrongPathReportsCannotBeUsed() throws Exception {
        var report = report();
        try (var stub = new GcsStub()) {
            stub.add(200, metadata("reports", path(report), "123", CanonicalReportCodec.MAX_BYTES + 1));
            assertThrows(IOException.class, () -> stub.adapter.find("reports", path(report)));
            assertEquals(1, stub.requests.size());
        }
        try (var stub = new GcsStub()) {
            stub.add(200, metadata("reports", path(report), "123", 2));
            stub.add(200, "{}".getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> stub.adapter.find("reports", path(report)));
        }
        try (var stub = new GcsStub()) {
            assertThrows(IOException.class, () -> stub.adapter.createIfAbsent("reports", "wrong/path", report));
            assertThrows(IllegalArgumentException.class, () -> stub.adapter.find("uploads", path(report)));
            assertTrue(stub.requests.isEmpty());
        }
    }

    @Test
    void absentReportDiffersFromFailedOrMissingPinnedRead() throws Exception {
        var report = report();
        byte[] missing = "{\"error\":{\"code\":404,\"message\":\"missing\"}}".getBytes(StandardCharsets.UTF_8);
        try (var stub = new GcsStub()) {
            stub.add(404, missing);
            assertTrue(stub.adapter.find("reports", path(report)).isEmpty());
        }
        try (var stub = new GcsStub()) {
            stub.add(200, metadata("reports", path(report), "123", codec.encode(report).length));
            stub.add(404, missing);
            assertThrows(IOException.class, () -> stub.adapter.find("reports", path(report)));
            assertEquals(2, stub.requests.size());
        }
        try (var stub = new GcsStub()) {
            stub.add(503, "{\"error\":{\"code\":503,\"message\":\"unavailable\"}}".getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> stub.adapter.createIfAbsent("reports", path(report), report));
            assertEquals(1, stub.requests.size());
        }
    }

    private record Reply(int status, byte[] bytes, String encoding) { }
    private record Request(String method, URI uri, String body) {
        Map<String, String> query() {
            var result = new HashMap<String, String>();
            if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                result.put(pair[0], pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
            }
            return result;
        }
    }

    private static class GcsStub implements AutoCloseable {
        final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final HttpServer server;
        final Storage client;
        final GcsProcessingStorage adapter;

        GcsStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                var body = exchange.getRequestBody();
                if ("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))) body = new GZIPInputStream(body);
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI(), new String(body.readAllBytes(), StandardCharsets.UTF_8)));
                Reply reply = replies.poll();
                if (reply == null) reply = new Reply(500, "{}".getBytes(StandardCharsets.UTF_8), null);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (reply.encoding() != null) exchange.getResponseHeaders().set("Content-Encoding", reply.encoding());
                exchange.sendResponseHeaders(reply.status(), reply.bytes().length);
                try (var output = exchange.getResponseBody()) { output.write(reply.bytes()); }
            });
            server.start();
            client = StorageOptions.newBuilder().setProjectId("test").setCredentials(NoCredentials.getInstance())
                    .setHost("http://127.0.0.1:" + server.getAddress().getPort())
                    .setTransportOptions(HttpTransportOptions.newBuilder().setConnectTimeout(2000).setReadTimeout(2000).build())
                    .setRetrySettings(StorageOptions.getDefaultRetrySettings().toBuilder().setMaxAttempts(1).build()).build().getService();
            adapter = new GcsProcessingStorage(client, "uploads", "reports");
        }

        void add(int status, byte[] bytes) { replies.add(new Reply(status, bytes, null)); }

        @Override public void close() throws Exception {
            try { client.close(); }
            finally { server.stop(0); }
        }
    }
}
