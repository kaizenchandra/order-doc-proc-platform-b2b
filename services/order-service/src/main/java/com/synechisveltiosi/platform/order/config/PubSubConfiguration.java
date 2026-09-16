package com.synechisveltiosi.platform.order.config;

import com.synechisveltiosi.platform.order.adapter.messaging.*;
import com.synechisveltiosi.platform.order.adapter.persistence.OutboxStore;
import com.google.cloud.pubsub.v1.Subscriber;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true")
public class PubSubConfiguration {
    @Bean(destroyMethod = "close") PubSubTransport pubSubTransport(MessagingProperties settings) throws Exception { return new PubSubTransport(settings); }
    @Bean OutboxRelay outboxRelay(OutboxStore store, EventCodec codec, PubSubTransport transport, MessagingProperties settings) {
        return new OutboxRelay(store, codec, transport, settings);
    }
    @Bean SmartLifecycle messagingLifecycle(PubSubTransport transport, ResultReceiver receiver, OutboxRelay relay) {
        return new SmartLifecycle() {
            private Subscriber subscriber;
            private ScheduledExecutorService scheduler;
            private volatile boolean running;
            public void start() {
                try {
                    subscriber = transport.subscriber(receiver);
                    subscriber.startAsync().awaitRunning(30, TimeUnit.SECONDS);
                    scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("outbox-relay").factory());
                    scheduler.scheduleWithFixedDelay(() -> {
                        try { relay.poll(); }
                        catch (RuntimeException failure) {
                            LoggerFactory.getLogger(PubSubConfiguration.class).error("Outbox poll failed; failureType={}", failure.getClass().getSimpleName());
                        }
                    }, 0, 1, TimeUnit.SECONDS);
                    running = true;
                } catch (Exception failure) { stop(); throw new IllegalStateException("Messaging failed to start", failure); }
            }
            public void stop() {
                running = false;
                if (scheduler != null) {
                    scheduler.shutdownNow();
                    try { scheduler.awaitTermination(10, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                if (subscriber != null) {
                    subscriber.stopAsync();
                    try { subscriber.awaitTerminated(15, TimeUnit.SECONDS); }
                    catch (TimeoutException timeout) { LoggerFactory.getLogger(PubSubConfiguration.class).warn("Subscriber shutdown timed out"); }
                }
            }
            public boolean isRunning() { return running && subscriber != null && subscriber.isRunning(); }
        };
    }
}
