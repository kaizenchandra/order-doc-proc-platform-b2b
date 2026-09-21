package com.synechisveltiosi.platform.order.adapter.storage;

import com.synechisveltiosi.platform.order.application.DocumentStorage;
import com.synechisveltiosi.platform.order.domain.GcsObjectReference;
import com.synechisveltiosi.platform.order.domain.VerifiedUpload;

import java.net.URI;
import java.time.Instant;

/**
 * fake-gcs-server requires URL host rewriting; it does not enforce signatures or expiry.
 */
public final class LocalDocumentStorage implements DocumentStorage {
    private final DocumentStorage delegate;
    private final String endpoint;

    public LocalDocumentStorage(DocumentStorage delegate, String endpoint) {
        URI uri = URI.create(endpoint);
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Local public endpoint must be an HTTP origin");
        this.delegate = delegate;
        this.endpoint = "http://" + uri.getRawAuthority();
    }

    private URI local(URI signed) {
        return URI.create(endpoint + signed.getRawPath() + "?" + signed.getRawQuery());
    }

    public UploadAuthorization authorize(String bucket, String name, String type, Instant expiry, long maxBytes) {
        var authorization = delegate.authorize(bucket, name, type, expiry, maxBytes);
        return new UploadAuthorization(local(authorization.url()), authorization.method(), authorization.headers(), authorization.expiresAt());
    }

    public VerifiedUpload inspect(String bucket, String name) {
        return delegate.inspect(bucket, name);
    }

    public DownloadAuthorization authorizeDownload(GcsObjectReference object, Instant expiry) {
        var authorization = delegate.authorizeDownload(object, expiry);
        return new DownloadAuthorization(local(authorization.url()), authorization.method(), authorization.headers(), authorization.expiresAt());
    }
}
