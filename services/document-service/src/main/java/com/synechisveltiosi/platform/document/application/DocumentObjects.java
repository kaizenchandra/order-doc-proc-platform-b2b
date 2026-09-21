package com.synechisveltiosi.platform.document.application;

import java.io.IOException;
import java.io.InputStream;

/**
 * The adapter must read the exact generation, never fall back to the latest object.
 */
public interface DocumentObjects {
    Input open(String bucket, String objectName, String generation) throws IOException;

    record Input(InputStream bytes, String contentType) implements AutoCloseable {
        public Input {
            java.util.Objects.requireNonNull(bytes);
        }

        @Override
        public void close() throws IOException {
            bytes.close();
        }
    }
}
