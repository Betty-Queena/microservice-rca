package com.microservice.rca.common;

import java.time.Instant;

/**
 * 统一 JSON 响应结构。code 与 HTTP 状态码保持一致，便于前端统一处理。
 */
public record ApiResponse<T>(int code, String message, T data, Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "OK", data, Instant.now());
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, Instant.now());
    }
}
