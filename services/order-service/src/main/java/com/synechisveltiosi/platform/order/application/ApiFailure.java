package com.synechisveltiosi.platform.order.application;

/**
 * Expected application failures with stable, client-safe descriptions.
 */
public class ApiFailure extends RuntimeException {
    private final int status;

    public ApiFailure(int status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiFailure notFound() {
        return new ApiFailure(404, "Resource not found");
    }

    public static ApiFailure conflict(String message) {
        return new ApiFailure(409, message);
    }

    public int status() {
        return status;
    }
}
