package com.microservice.rca.experiment;

import com.microservice.rca.diagnosis.domain.FaultSeverity;

/**
 * 根据订单服务的真实 HTTP 响应、下游错误标记和耗时判定实际观测结果。
 * 判定结果描述“观测到了什么”，不推断故障根因，也不把实验预设类型当作结论。
 */
public final class ObservationClassifier {

    public static final long DEFAULT_SLOW_THRESHOLD_MS = 3000;

    private ObservationClassifier() {
    }

    public static Observation classify(Integer httpStatus,
                                       String downstreamErrorType,
                                       long durationMs,
                                       long slowThresholdMs) {
        int status = httpStatus != null ? httpStatus : 0;
        if (status == 0) {
            return new Observation("ORDER_SERVICE_UNREACHABLE", true, FaultSeverity.ERROR,
                    "无法连接订单服务，未获得真实业务响应");
        }
        if (status >= 200 && status < 300) {
            if (durationMs >= slowThresholdMs) {
                return new Observation("SLOW_RESPONSE", true, FaultSeverity.WARNING,
                        "订单结算请求成功，但耗时 " + durationMs + " ms，超过慢响应阈值 "
                                + slowThresholdMs + " ms");
            }
            return new Observation("SUCCESS", false, FaultSeverity.INFO,
                    "订单结算请求成功，耗时 " + durationMs + " ms");
        }
        if (status == 409) {
            return new Observation("BUSINESS_CONFLICT", false, FaultSeverity.INFO,
                    "库存不足或业务规则拒绝，属于业务冲突，不记为系统故障");
        }
        if (status == 502 || status == 503 || status == 504) {
            String type = downstreamErrorType == null || downstreamErrorType.isBlank()
                    ? "UNKNOWN" : downstreamErrorType;
            return switch (type) {
                case "TIMEOUT" -> new Observation("DOWNSTREAM_TIMEOUT", true, FaultSeverity.ERROR,
                        "订单结算调用库存服务超时（下游错误类型 TIMEOUT）");
                case "HTTP_5XX" -> new Observation("DOWNSTREAM_HTTP_5XX", true, FaultSeverity.ERROR,
                        "订单结算调用库存服务返回 5xx（下游错误类型 HTTP_5XX）");
                case "CONNECTION_ERROR" -> new Observation("DOWNSTREAM_CONNECTION_ERROR", true,
                        FaultSeverity.ERROR, "订单服务无法连接库存服务（下游错误类型 CONNECTION_ERROR）");
                case "PROTOCOL_ERROR" -> new Observation("DOWNSTREAM_PROTOCOL_ERROR", true,
                        FaultSeverity.ERROR, "库存服务响应格式不符合预期（下游错误类型 PROTOCOL_ERROR）");
                default -> new Observation("DOWNSTREAM_ERROR", true, FaultSeverity.ERROR,
                        "订单服务调用下游失败（HTTP " + status + "，下游错误类型 " + type + "）");
            };
        }
        if (status >= 500) {
            return new Observation("SERVER_ERROR", true, FaultSeverity.ERROR,
                    "订单服务返回服务器错误（HTTP " + status + "）");
        }
        if (status >= 400) {
            return new Observation("REQUEST_REJECTED", false, FaultSeverity.INFO,
                    "订单服务拒绝请求（HTTP " + status + "），属于请求问题，不记为系统故障");
        }
        return new Observation("UNEXPECTED_STATUS", true, FaultSeverity.WARNING,
                "订单服务返回非预期状态码（HTTP " + status + "）");
    }

    public record Observation(
            String outcome,
            boolean faultObserved,
            FaultSeverity severity,
            String summary) {
    }
}
