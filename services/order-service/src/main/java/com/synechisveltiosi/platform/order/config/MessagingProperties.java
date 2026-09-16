package com.synechisveltiosi.platform.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.messaging")
public record MessagingProperties(@DefaultValue("false") boolean enabled,
                                  @DefaultValue("") String projectId, @DefaultValue("") String emulatorHost,
                                  @DefaultValue("order-events") String orderEventsTopic,
                                  @DefaultValue("document-requests") String requestsTopic,
                                  @DefaultValue("order-document-results") String resultsSubscription,
                                  @DefaultValue("") String reportBucket, @DefaultValue("90") int leaseSeconds,
                                  @DefaultValue("20") int maxPerPoll) {
    public MessagingProperties {
        if (leaseSeconds < 60 || leaseSeconds > 600 || maxPerPoll < 1 || maxPerPoll > 100)
            throw new IllegalArgumentException("Invalid relay bounds");
        if (enabled && (projectId.isBlank() || reportBucket.isBlank()))
            throw new IllegalArgumentException("Messaging project and report bucket are required");
        for (String name : new String[]{orderEventsTopic, requestsTopic, resultsSubscription})
            if (!name.matches("[A-Za-z][A-Za-z0-9._~-]{2,254}"))
                throw new IllegalArgumentException("Invalid Pub/Sub resource ID");
        if (orderEventsTopic.equals(requestsTopic)) throw new IllegalArgumentException("Topics must be distinct");
    }
}
