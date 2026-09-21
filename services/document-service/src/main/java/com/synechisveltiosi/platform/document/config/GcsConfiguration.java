package com.synechisveltiosi.platform.document.config;

import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.synechisveltiosi.platform.document.adapter.storage.GcsProcessingStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

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
    GcsProcessingStorage processingStorage(Storage storage, @Value("${app.storage.upload-bucket}") String uploads,
                                          @Value("${app.storage.report-bucket}") String reports) {
        return new GcsProcessingStorage(storage, uploads, reports);
    }
}
