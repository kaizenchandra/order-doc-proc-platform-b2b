package com.synechisveltiosi.platform.order.adapter.messaging;

import java.util.Map;

public interface EventPublisher {
    /**
     * Return only after Pub/Sub accepts publication; timeout/failure may still mean it accepted the event.
     */
    void publish(String destination, byte[] event, Map<String, String> attributes) throws Exception;
}
