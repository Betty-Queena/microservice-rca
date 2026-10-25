package com.microservice.rca.external.client;

/**
 * 外部服务只读请求失败。消息只包含稳定的错误类别，不携带响应正文或认证信息。
 */
public class ExternalServiceException extends RuntimeException {

    public ExternalServiceException(String message) {
        super(message);
    }

    public ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
