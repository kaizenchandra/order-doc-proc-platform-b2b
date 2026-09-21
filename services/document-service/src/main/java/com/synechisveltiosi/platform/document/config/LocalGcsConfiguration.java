package com.synechisveltiosi.platform.document.config;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Explicit development profile. Never uses ADC or a cloud signing identity.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "app.storage.enabled", havingValue = "true")
public class LocalGcsConfiguration {
    @Bean(destroyMethod = "close")
    Storage localGcsClient(@Value("${app.local.gcs-endpoint}") String endpoint) {
        var uri = java.net.URI.create(endpoint);
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null)
            throw new IllegalArgumentException("Local GCS endpoint must be explicit HTTP");
        return StorageOptions.newBuilder().setProjectId("local-platform").setHost(endpoint)
                .setCredentials(NoCredentials.getInstance())
                .setTransportOptions(com.google.cloud.http.HttpTransportOptions.newBuilder()
                        .setConnectTimeout(3000).setReadTimeout(10000).build())
                .setRetrySettings(StorageOptions.getDefaultRetrySettings().toBuilder().setMaxAttempts(3)
                        .setTotalTimeoutDuration(java.time.Duration.ofSeconds(20)).build()).build().getService();
    }

    @Bean
    com.synechisveltiosi.platform.document.adapter.storage.GcsProcessingStorage localProcessingStorage(
            Storage storage, @Value("${app.storage.upload-bucket}") String uploads,
            @Value("${app.storage.report-bucket}") String reports) {
        return new com.synechisveltiosi.platform.document.adapter.storage.GcsProcessingStorage(storage, uploads, reports);
    }
}
