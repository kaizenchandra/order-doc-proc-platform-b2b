package com.synechisveltiosi.platform.order.adapter.messaging;

import com.synechisveltiosi.platform.order.application.DocumentResultHandler;
import com.synechisveltiosi.platform.commonobservability.OperationObservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ResultReceiver {
    private static final Logger log = LoggerFactory.getLogger(ResultReceiver.class);
    private final EventCodec codec;
    private final DocumentResultHandler handler;

    public ResultReceiver(EventCodec codec, DocumentResultHandler handler) {
        this.codec = codec;
        this.handler = handler;
    }

    public void receive(byte[] bytes, Acknowledgement acknowledgement) {
        receive(bytes, java.util.Map.of(), acknowledgement);
    }

    public void receive(byte[] bytes, java.util.Map<String, String> attributes, Acknowledgement acknowledgement) {
        try (var ignored = com.synechisveltiosi.platform.commonobservability.TraceContext.open(attributes.get("traceparent"));
             var observation = new OperationObservation(OperationObservation.Operation.ORDER_RESULT)) {
            handler.handle(codec.decode(bytes));
            observation.succeeded();
        } catch (RuntimeException failure) {
            // No ACK for malformed/unsupported events: subscription retry/DLQ policy owns bounded delivery.
            log.warn("Result rejected or transaction failed; failureType={}", failure.getClass().getSimpleName());
            acknowledgement.nack();
            return;
        }
        // The transactional proxy has committed before this point. ACK failure permits harmless redelivery.
        acknowledgement.ack();
    }

    public interface Acknowledgement {
        void ack();

        void nack();
    }
}
