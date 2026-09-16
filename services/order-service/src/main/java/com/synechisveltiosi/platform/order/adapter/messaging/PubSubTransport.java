package com.synechisveltiosi.platform.order.adapter.messaging;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.batching.FlowControlSettings;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.*;
import com.synechisveltiosi.platform.order.config.MessagingProperties;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Uses ADC/TLS normally; plaintext and no credentials only when explicitly configured for an emulator. */
public class PubSubTransport implements EventPublisher, AutoCloseable {
    private final Map<String, Publisher> publishers = new HashMap<>();
    private final ManagedChannel emulatorChannel;
    private final MessagingProperties settings;
    private final FixedTransportChannelProvider emulatorProvider;
    public PubSubTransport(MessagingProperties settings) throws Exception {
        this.settings = settings;
        emulatorChannel = settings.emulatorHost().isBlank() ? null
                : ManagedChannelBuilder.forTarget(settings.emulatorHost()).usePlaintext().build();
        emulatorProvider = emulatorChannel == null ? null : FixedTransportChannelProvider.create(GrpcTransportChannel.create(emulatorChannel));
        try {
            publishers.put("order-events", publisher(settings.orderEventsTopic()));
            publishers.put("document-requests", publisher(settings.requestsTopic()));
        } catch (Exception failure) { close(); throw failure; }
    }
    private Publisher publisher(String topic) throws Exception {
        var builder = Publisher.newBuilder(TopicName.of(settings.projectId(), topic));
        if (emulatorProvider != null) builder.setChannelProvider(emulatorProvider).setCredentialsProvider(NoCredentialsProvider.create());
        // Bound SDK retries below the 30-second caller deadline and the 90-second default SQL lease.
        builder.setRetrySettings(com.google.api.gax.retrying.RetrySettings.newBuilder()
                .setInitialRetryDelayDuration(java.time.Duration.ofMillis(100))
                .setMaxRetryDelayDuration(java.time.Duration.ofSeconds(2)).setRetryDelayMultiplier(2)
                .setRpcTimeoutMultiplier(1)
                .setTotalTimeoutDuration(java.time.Duration.ofSeconds(20))
                .setInitialRpcTimeoutDuration(java.time.Duration.ofSeconds(5))
                .setMaxRpcTimeoutDuration(java.time.Duration.ofSeconds(10)).build());
        return builder.build();
    }
    @Override public void publish(String destination, byte[] bytes, Map<String, String> attributes) throws Exception {
        var publisher = publishers.get(destination);
        if (publisher == null) throw new IllegalArgumentException("Unknown destination");
        var future = publisher.publish(PubsubMessage.newBuilder().setData(ByteString.copyFrom(bytes)).putAllAttributes(attributes).build());
        try { future.get(30, TimeUnit.SECONDS); }
        catch (Exception failure) { future.cancel(true); throw failure; }
    }
    public Subscriber subscriber(ResultReceiver receiver) {
        var builder = Subscriber.newBuilder(ProjectSubscriptionName.of(settings.projectId(), settings.resultsSubscription()),
                (com.google.cloud.pubsub.v1.MessageReceiver) (message, consumer) -> receiver.receive(message.getData().toByteArray(), message.getAttributesMap(), new ResultReceiver.Acknowledgement() {
                    public void ack() { consumer.ack(); }
                    public void nack() { consumer.nack(); }
                }))
                .setParallelPullCount(1)
                .setFlowControlSettings(FlowControlSettings.newBuilder().setMaxOutstandingElementCount(20L)
                        .setMaxOutstandingRequestBytes(4L * 1024 * 1024).build())
                .setExecutorProvider(com.google.api.gax.core.InstantiatingExecutorProvider.newBuilder().setExecutorThreadCount(4).build());
        if (emulatorProvider != null) builder.setChannelProvider(emulatorProvider).setCredentialsProvider(NoCredentialsProvider.create());
        return builder.build();
    }
    @Override public void close() {
        publishers.values().forEach(Publisher::shutdown);
        for (var publisher : publishers.values()) {
            try { publisher.awaitTermination(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        if (emulatorChannel != null) emulatorChannel.shutdownNow();
    }
}
