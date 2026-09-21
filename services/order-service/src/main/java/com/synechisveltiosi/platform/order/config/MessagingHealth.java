package com.synechisveltiosi.platform.order.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Readiness only: a terminal subscriber failure must not leave a healthy-looking consumer. */
@Component("messagingHealthIndicator")
public class MessagingHealth implements HealthIndicator {
    private final ObjectProvider<SmartLifecycle> lifecycle;

    public MessagingHealth(@Qualifier("messagingLifecycle") ObjectProvider<SmartLifecycle> lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public Health health() {
        var worker = lifecycle.getIfAvailable();
        return (worker == null || worker.isRunning() ? Health.up() : Health.down()).build();
    }
}
