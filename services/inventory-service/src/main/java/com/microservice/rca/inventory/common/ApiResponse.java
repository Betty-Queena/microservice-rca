package com.microservice.rca.inventory.common;

import java.time.Instant;

public record ApiResponse<T>(int code, String message, T data, Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "OK", data, Instant.now());
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, Instant.now());
    }
}
