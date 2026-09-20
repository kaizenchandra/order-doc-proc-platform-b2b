package com.synechisveltiosi.platform.order.adapter.storage;

import com.google.auth.ServiceAccountSigner;
import com.google.cloud.NoCredentials;
import com.google.cloud.storage.*;
import com.synechisveltiosi.platform.order.application.ApiFailure;
import com.synechisveltiosi.platform.order.domain.GcsObjectReference;
import com.synechisveltiosi.platform.order.domain.VerifiedUpload;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GcsDocumentStorageTest {
    private static class CapturingSigner implements ServiceAccountSigner {
        String payload;
        public String getAccount() { return "signer@example.iam.gserviceaccount.com"; }
        public byte[] sign(byte[] bytes) {
            payload = new String(bytes, StandardCharsets.UTF_8);
            return new byte[]{1, 2, 3}; // Only signature construction is under test; no private key or IAM call.
        }
    }

    private Map<String, String> query(URI uri) {
        var values = new HashMap<String, String>();
        for (String part : uri.getRawQuery().split("&")) {
            var pair = part.split("=", 2);
            values.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    @Test
    void uploadSignatureBindsMethodObjectContentTypeCreateOnlyAndSizeBounds() throws Exception {
        var signer = new CapturingSigner();
        try (var client = StorageOptions.newBuilder().setProjectId("test").setCredentials(NoCredentials.getInstance()).build().getService()) {
            var adapter = new GcsDocumentStorage(client, signer, Clock.systemUTC(), "uploads", "reports");
            Instant deadline = Instant.now().plusSeconds(600);
            var auth = adapter.authorize("uploads", "tenant/a b.pdf", "application/pdf", deadline, VerifiedUpload.MAX_SIZE_BYTES);
            assertEquals("PUT", auth.method());
            assertTrue(auth.url().getRawPath().endsWith("/tenant/a%20b.pdf"));
            assertTrue(auth.expiresAt().isAfter(Instant.now()));
            assertFalse(auth.expiresAt().isAfter(deadline));
            var query = query(auth.url());
            assertEquals("GOOG4-RSA-SHA256", query.get("X-Goog-Algorithm"));
            assertEquals("content-type;host;x-goog-content-length-range;x-goog-if-generation-match", query.get("X-Goog-SignedHeaders"));
            assertEquals("0", auth.headers().get("x-goog-if-generation-match"));
            assertEquals("1," + VerifiedUpload.MAX_SIZE_BYTES, auth.headers().get("x-goog-content-length-range"));
            String unsignedQuery = auth.url().getRawQuery().replaceAll("&X-Goog-Signature=[^&]+", "");
            String canonical = "PUT\n" + auth.url().getRawPath() + "\n" + unsignedQuery + "\n"
                    + "content-type:application/pdf\nhost:" + auth.url().getHost() + "\nx-goog-content-length-range:1,"
                    + VerifiedUpload.MAX_SIZE_BYTES + "\nx-goog-if-generation-match:0\n\n" + query.get("X-Goog-SignedHeaders") + "\nUNSIGNED-PAYLOAD";
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            assertTrue(signer.payload.endsWith("\n" + hash), "All upload constraints must enter the signed canonical request");
        }
    }

    @Test
    void reportDownloadPinsGenerationAndExpiresWithinFiveMinutes() throws Exception {
        var signer = new CapturingSigner();
        try (var client = StorageOptions.newBuilder().setProjectId("test").setCredentials(NoCredentials.getInstance()).build().getService()) {
            var adapter = new GcsDocumentStorage(client, signer, Clock.systemUTC(), "uploads", "reports");
            Instant deadline = Instant.now().plusSeconds(300);
            var auth = adapter.authorizeDownload(new GcsObjectReference("reports", "tenant/request/result.json", 123), deadline);
            var parameters = query(auth.url());
            assertEquals("GET", auth.method());
            assertEquals("123", parameters.get("generation"));
            assertEquals("attachment; filename=report.json", parameters.get("response-content-disposition"));
            assertTrue(Long.parseLong(parameters.get("X-Goog-Expires")) <= 300);
            assertFalse(auth.expiresAt().isAfter(deadline));
        }
    }

    @Test
    void expiredAndWrongBucketAuthorizationsNeverReachSigner() {
        var storage = mock(Storage.class);
        var signer = mock(ServiceAccountSigner.class);
        var adapter = new GcsDocumentStorage(storage, signer, Clock.systemUTC(), "uploads", "reports");
        assertEquals(409, assertThrows(ApiFailure.class, () -> adapter.authorize("uploads", "x", "application/pdf", Instant.now().minusSeconds(1), 100)).status());
        assertThrows(IllegalArgumentException.class, () -> adapter.authorize("other", "x", "application/pdf", Instant.now().plusSeconds(60), 100));
        assertThrows(IllegalArgumentException.class, () -> adapter.authorizeDownload(new GcsObjectReference("uploads", "x", 1), Instant.now().plusSeconds(60)));
        verifyNoInteractions(storage, signer);
    }

    @Test
    void inspectionUsesServerMetadataAndSanitizesStorageErrors() {
        var storage = mock(Storage.class);
        var blob = mock(Blob.class);
        when(blob.getBucket()).thenReturn("uploads");
        when(blob.getName()).thenReturn("x");
        when(blob.getGeneration()).thenReturn(42L);
        when(blob.getSize()).thenReturn(100L);
        when(storage.get(BlobId.of("uploads", "x"))).thenReturn(blob);
        var adapter = new GcsDocumentStorage(storage, mock(ServiceAccountSigner.class), Clock.systemUTC(), "uploads", "reports");
        assertEquals(new VerifiedUpload(new GcsObjectReference("uploads", "x", 42), 100), adapter.inspect("uploads", "x"));
        when(blob.getSize()).thenReturn(VerifiedUpload.MAX_SIZE_BYTES + 1);
        assertEquals(422, assertThrows(ApiFailure.class, () -> adapter.inspect("uploads", "x")).status());
        when(storage.get(BlobId.of("uploads", "x"))).thenReturn(null);
        assertEquals(409, assertThrows(ApiFailure.class, () -> adapter.inspect("uploads", "x")).status());
        when(storage.get(BlobId.of("uploads", "x"))).thenThrow(new StorageException(403, "sensitive provider message"));
        var failure = assertThrows(ApiFailure.class, () -> adapter.inspect("uploads", "x"));
        assertEquals(503, failure.status());
        assertFalse(failure.getMessage().contains("sensitive"));
    }
}
