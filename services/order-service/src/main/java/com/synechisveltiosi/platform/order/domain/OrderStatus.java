package com.synechisveltiosi.platform.order.domain;

public enum OrderStatus {
    CREATED, CONFIRMED, FULFILLED, CANCELLED;

    public boolean allows(OrderStatus target) {
        return switch (this) {
            case CREATED -> target == CONFIRMED || target == CANCELLED;
            case CONFIRMED -> target == FULFILLED || target == CANCELLED;
            case FULFILLED, CANCELLED -> false;
        };
    }
}
