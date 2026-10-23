package com.microservice.rca.inventory.observability;

import org.slf4j.MDC;

/**
 * 请求标识传递工具。订单服务生成或透传 X-Request-ID，
 * 并通过同一请求头传递给库存服务，日志可据此关联跨服务调用。
 */
public final class RequestIdContext {

    public static final String HEADER = "X-Request-ID";
    public static final String MDC_KEY = "requestId";
    public static final String ATTRIBUTE = RequestIdContext.class.getName() + ".requestId";

    private RequestIdContext() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 64 || !trimmed.matches("[A-Za-z0-9._:-]+")) {
            return null;
        }
        return trimmed;
    }
}
