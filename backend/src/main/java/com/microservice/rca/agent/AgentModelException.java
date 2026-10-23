package com.microservice.rca.agent;

/**
 * 模型调用失败。failureType 用于审计和前端提示，不包含 API Key 或请求头。
 */
public class AgentModelException extends RuntimeException {

    private final String failureType;
    private final boolean retryable;

    public AgentModelException(String failureType, String message, boolean retryable) {
        super(message);
        this.failureType = failureType;
        this.retryable = retryable;
    }

    public AgentModelException(String failureType, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.failureType = failureType;
        this.retryable = retryable;
    }

    public String getFailureType() {
        return failureType;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
