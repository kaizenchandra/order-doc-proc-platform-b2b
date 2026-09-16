package com.synechisveltiosi.platform.order.config;

import com.synechisveltiosi.platform.order.application.*;
import com.synechisveltiosi.platform.order.domain.VerifiedUpload;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.Instant;

@Configuration(proxyBeanMethods = false)
public class WorkflowConfiguration {
    @Bean @ConditionalOnMissingBean Clock workflowClock() { return Clock.systemUTC(); }
    @Bean @ConditionalOnMissingBean DocumentStorage documentStorage() {
        return new DocumentStorage() {
            public UploadAuthorization authorize(String bucket, String objectName, String type, Instant expires, long maxBytes) {
                throw unavailable();
            }
            public VerifiedUpload inspect(String bucket, String objectName) { throw unavailable(); }
            private ApiFailure unavailable() { return new ApiFailure(503, "Document storage is not configured"); }
        };
    }
}
