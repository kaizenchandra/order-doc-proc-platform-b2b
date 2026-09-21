package com.synechisveltiosi.platform.document.config;

import com.synechisveltiosi.platform.document.application.DocumentObjects;
import com.synechisveltiosi.platform.document.application.DocumentProcessor;
import com.synechisveltiosi.platform.document.application.ReportStore;
import com.synechisveltiosi.platform.document.application.ResultPublisher;
import com.synechisveltiosi.platform.document.domain.CanonicalReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Clock;
import java.util.Optional;

@Configuration(proxyBeanMethods = false)
public class ProcessingConfiguration {
    @Bean
    DocumentProcessor processor(DocumentObjects objects, ReportStore reports, ResultPublisher publisher,
                                @Value("${app.storage.upload-bucket}") String uploadBucket,
                                @Value("${app.storage.report-bucket}") String reportBucket) {
        return new DocumentProcessor(objects, reports, publisher, Clock.systemUTC(), uploadBucket, reportBucket, "1");
    }

    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.storage.enabled", havingValue = "false", matchIfMissing = true)
    DocumentObjects documentObjects() {
        return (bucket, name, generation) -> {
            throw new IOException("Document storage is not configured");
        };
    }

    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.storage.enabled", havingValue = "false", matchIfMissing = true)
    ReportStore reportStore() {
        return new ReportStore() {
            public Optional<Stored> find(String bucket, String name) throws IOException {
                throw new IOException("Report storage is not configured");
            }

            public Stored createIfAbsent(String bucket, String name, CanonicalReport report) throws IOException {
                throw new IOException("Report storage is not configured");
            }
        };
    }
}
