package com.synechisveltiosi.platform.order.config;

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
    com.synechisveltiosi.platform.order.application.DocumentStorage localDocumentStorage(
            Storage storage, java.time.Clock clock, @Value("${app.storage.upload-bucket}") String uploads,
            @Value("${app.storage.report-bucket}") String reports,
            @Value("${app.local.gcs-public-endpoint}") String publicEndpoint) throws java.security.GeneralSecurityException {
        var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var privateKey = generator.generateKeyPair().getPrivate();
        var signer = new com.google.auth.ServiceAccountSigner() {
            public String getAccount() {
                return "local-signer@local-platform.invalid";
            }

            public byte[] sign(byte[] bytes) {
                try {
                    var signature = java.security.Signature.getInstance("SHA256withRSA");
                    signature.initSign(privateKey);
                    signature.update(bytes);
                    return signature.sign();
                } catch (java.security.GeneralSecurityException failure) {
                    throw new IllegalStateException("Local signing failed", failure);
                }
            }
        };
        var delegate = new com.synechisveltiosi.platform.order.adapter.storage.GcsDocumentStorage(
                storage, signer, clock, uploads, reports);
        return new com.synechisveltiosi.platform.order.adapter.storage.LocalDocumentStorage(delegate, publicEndpoint);
    }
}
