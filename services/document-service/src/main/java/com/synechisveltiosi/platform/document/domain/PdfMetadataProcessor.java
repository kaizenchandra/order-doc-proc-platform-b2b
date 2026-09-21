package com.synechisveltiosi.platform.document.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Bounded PDF signature/trailer inspection and checksum, not a full PDF parser or malware scanner.
 */
public final class PdfMetadataProcessor {
    public static final long MAX_BYTES = 25L * 1024 * 1024;

    public Outcome inspect(InputStream input, String contentType) throws IOException {
        if (!"application/pdf".equals(contentType)) return new Outcome(0, null, "UNSUPPORTED_FORMAT");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        byte[] buffer = new byte[8192];
        byte[] header = new byte[8];
        byte[] tail = new byte[1024];
        long count = 0;
        int read;
        while ((read = input.read(buffer, 0, (int) Math.min(buffer.length, MAX_BYTES + 1 - count))) != -1) {
            for (int i = 0; i < read; i++) {
                if (count + i < header.length) header[(int) count + i] = buffer[i];
                tail[(int) ((count + i) % tail.length)] = buffer[i];
            }
            count += read;
            if (count > MAX_BYTES) return new Outcome(count, null, "DOCUMENT_TOO_LARGE");
            digest.update(buffer, 0, read);
        }
        if (count == 0) return new Outcome(0, null, "EMPTY_DOCUMENT");
        int tailSize = (int) Math.min(count, tail.length);
        byte[] orderedTail = new byte[tailSize];
        for (int i = 0; i < tailSize; i++) orderedTail[i] = tail[(int) ((count - tailSize + i) % tail.length)];
        String start = new String(header, StandardCharsets.US_ASCII);
        String end = new String(orderedTail, StandardCharsets.US_ASCII).stripTrailing();
        if (!start.matches("%PDF-[12]\\.[0-9]") || !end.endsWith("%%EOF"))
            return new Outcome(count, null, "INVALID_PDF");
        return new Outcome(count, HexFormat.of().formatHex(digest.digest()), null);
    }

    public record Outcome(long bytesRead, String sha256, String failureCode) {
    }
}
