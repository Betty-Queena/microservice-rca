package com.microservice.rca.experiment.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * 一次真实实验请求的执行结果。presetFaultType 只是实验预设，不是根因结论。
 */
public record ExperimentExecutionView(
        UUID experimentId,
        String requestId,
        String presetFaultType,
        String outcome,
        boolean faultObserved,
        Integer httpStatus,
        long durationMs,
        String downstreamErrorType,
        String orderStatus,
        String orderId,
        Integer remainingStock,
        JsonNode responseBody,
        UUID faultEventId,
        Instant executedAt,
        String note) {
}
