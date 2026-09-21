package com.synechisveltiosi.platform.order.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.SmartLifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class MessagingHealthTest {
    @Test
    @SuppressWarnings("unchecked")
    void workerFailureChangesReadinessAndDisabledMessagingRemainsHealthy() {
        var provider = (ObjectProvider<SmartLifecycle>) mock(ObjectProvider.class);
        var health = new MessagingHealth(provider);
        assertEquals(Status.UP, health.health().getStatus());
        var worker = mock(SmartLifecycle.class);
        when(provider.getIfAvailable()).thenReturn(worker);
        when(worker.isRunning()).thenReturn(true, false);
        assertEquals(Status.UP, health.health().getStatus());
        assertEquals(Status.DOWN, health.health().getStatus());
    }
}
