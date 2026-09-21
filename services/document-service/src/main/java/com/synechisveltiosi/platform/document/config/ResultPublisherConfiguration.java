package com.synechisveltiosi.platform.document.config;

import com.synechisveltiosi.platform.document.adapter.messaging.PubSubResultPublisher;
import com.synechisveltiosi.platform.document.application.ResultPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ResultPublisherConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ResultPublisher.class)
    @ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true")
    PubSubResultPublisher pubSubResultPublisher(@Value("${app.messaging.project-id}") String project,
                                                @Value("${app.messaging.results-topic}") String topic,
                                                @Value("${app.messaging.emulator-host}") String emulator) throws Exception {
        return new PubSubResultPublisher(project, topic, emulator);
    }

    @Bean
    @ConditionalOnMissingBean(ResultPublisher.class)
    @ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "false", matchIfMissing = true)
    ResultPublisher unavailableResultPublisher() {
        return event -> {
            throw new IllegalStateException("Result publisher is not configured");
        };
    }
}
