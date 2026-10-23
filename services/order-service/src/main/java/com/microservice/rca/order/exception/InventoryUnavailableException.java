package com.microservice.rca.order.exception;

/**
 * 下游库存服务不可用。errorType 用于区分超时、5xx 与连接失败，
 * 便于观测日志和后续诊断工具区分故障类型。
 */
public class InventoryUnavailableException extends RuntimeException {

    private final String errorType;

    public InventoryUnavailableException(String message) {
        this(message, "DOWNSTREAM_ERROR");
    }

    public InventoryUnavailableException(String message, String errorType) {
        super(message);
        this.errorType = errorType;
    }

    public InventoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
        this.errorType = "DOWNSTREAM_ERROR";
    }

    public String getErrorType() {
        return errorType;
    }
}
