package com.synechisveltiosi.platform.document.adapter.messaging;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.retrying.RetrySettings;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import com.synechisveltiosi.platform.commonobservability.TraceContext;
import com.synechisveltiosi.platform.document.application.ResultPublisher;
import com.synechisveltiosi.platform.eventcontracts.Events;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class PubSubResultPublisher implements ResultPublisher, AutoCloseable {
    private final Publisher publisher;
    private final ManagedChannel channel;
    private final JsonMapper json = JsonMapper.builder().build();

    public PubSubResultPublisher(String project, String topic, String emulatorHost) throws Exception {
        if (project.isBlank() || !topic.matches("[A-Za-z][A-Za-z0-9._~-]{2,254}"))
            throw new IllegalArgumentException("Result publisher configuration is required");
        channel = emulatorHost.isBlank() ? null : ManagedChannelBuilder.forTarget(emulatorHost).usePlaintext().build();
        try {
            var builder = Publisher.newBuilder(TopicName.of(project, topic));
            if (channel != null)
                builder.setChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
                        .setCredentialsProvider(NoCredentialsProvider.create());
            builder.setRetrySettings(RetrySettings.newBuilder().setInitialRetryDelayDuration(Duration.ofMillis(100))
                    .setMaxRetryDelayDuration(Duration.ofSeconds(2)).setRetryDelayMultiplier(2)
                    .setRpcTimeoutMultiplier(1).setInitialRpcTimeoutDuration(Duration.ofSeconds(5))
                    .setMaxRpcTimeoutDuration(Duration.ofSeconds(10)).setTotalTimeoutDuration(Duration.ofSeconds(20)).build());
            publisher = builder.build();
        } catch (Exception failure) {
            if (channel != null) channel.shutdownNow();
            throw failure;
        }
    }

    @Override
    public void publish(Events.Envelope event) throws Exception {
        if (!(event.data() instanceof Events.DocumentResult)) throw new IllegalArgumentException("Expected result");
        byte[] bytes = json.writeValueAsBytes(event);
        if (bytes.length > 64 * 1024) throw new IllegalArgumentException("Result too large");
        var message = PubsubMessage.newBuilder().setData(ByteString.copyFrom(bytes))
                .putAttributes("eventId", event.eventId().toString()).putAttributes("correlationId", event.correlationId().toString());
        if (TraceContext.valid(TraceContext.current())) message.putAttributes("traceparent", TraceContext.current());
        var future = publisher.publish(message.build());
        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (Exception failure) {
            future.cancel(true);
            throw failure;
        }
    }

    @Override
    public void close() {
        publisher.shutdown();
        try {
            publisher.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (channel != null) channel.shutdownNow();
        }
    }
}
