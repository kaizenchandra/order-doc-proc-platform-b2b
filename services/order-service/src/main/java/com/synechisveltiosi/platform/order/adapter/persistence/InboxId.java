package com.synechisveltiosi.platform.order.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class InboxId implements Serializable {
    @Column(name = "consumer_name", length = 100)
    private String consumerName;
    @Column(name = "event_id")
    private UUID eventId;

    protected InboxId() {
    }

    public InboxId(String consumerName, UUID eventId) {
        this.consumerName = Objects.requireNonNull(consumerName);
        this.eventId = Objects.requireNonNull(eventId);
    }

    public String consumerName() {
        return consumerName;
    }

    public UUID eventId() {
        return eventId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof InboxId that && Objects.equals(consumerName, that.consumerName) && Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(consumerName, eventId);
    }
}
