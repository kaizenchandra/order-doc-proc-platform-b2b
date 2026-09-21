package com.synechisveltiosi.platform.order.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.synechisveltiosi.platform.order.adapter.storage.GcsDocumentStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@org.springframework.context.annotation.Profile("!local")
@ConditionalOnProperty(name = "app.storage.enabled", havingValue = "true")
public class GcsConfiguration {
    @Bean(destroyMethod = "close")
    Storage gcsClient() {
        return StorageOptions.newBuilder()
                .setTransportOptions(HttpTransportOptions.newBuilder().setConnectTimeout(3000).setReadTimeout(10000).build())
                .setRetrySettings(StorageOptions.getDefaultRetrySettings().toBuilder().setMaxAttempts(3)
                        .setTotalTimeoutDuration(Duration.ofSeconds(20)).build()).build().getService();
    }

    @Bean
    GcsDocumentStorage documentStorageAdapter(Storage storage, Clock clock,
                                              @Value("${app.storage.upload-bucket}") String uploads,
                                              @Value("${app.storage.report-bucket}") String reports,
                                              @Value("${app.storage.signing-service-account}") String signerEmail) throws IOException {
        if (signerEmail.isBlank()) throw new IllegalArgumentException("Signing service account is required");
        var scopes = List.of("https://www.googleapis.com/auth/cloud-platform");
        // Uses IAM signBlob through ADC; no service-account private key is loaded by the application.
        var signer = ImpersonatedCredentials.create(GoogleCredentials.getApplicationDefault().createScoped(scopes),
                signerEmail, null, scopes, 300);
        return new GcsDocumentStorage(storage, signer, clock, uploads, reports);
    }
}
