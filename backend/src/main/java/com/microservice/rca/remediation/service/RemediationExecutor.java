package com.microservice.rca.remediation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservice.rca.common.JsonSupport;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.experiment.ExperimentService;
import com.microservice.rca.experiment.api.CheckoutRequest;
import com.microservice.rca.experiment.api.ExperimentExecutionView;
import com.microservice.rca.experiment.api.ExperimentFaultStatusView;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 处置动作与恢复验证的外部执行器。
 *
 * <p>该类不访问数据库，也不开启事务。动作只调用阶段 1 的条件关闭接口
 * {@link ExperimentService#disable(UUID)}，绝不调用无条件关闭或重置接口。
 * 验证参数只从持久化故障事件的 details 读取。</p>
 */
@Service
public class RemediationExecutor {

    private static final Logger log = LoggerFactory.getLogger(RemediationExecutor.class);

    private static final String CRITERIA_VERSION = "day8-v1";

    private final ExperimentService experimentService;
    private final JsonSupport jsonSupport;
    private final long slowResponseThresholdMs;

    public RemediationExecutor(ExperimentService experimentService,
                               JsonSupport jsonSupport,
                               @Value("${experiment.slow-response-threshold-ms:3000}")
                               long slowResponseThresholdMs) {
        this.experimentService = experimentService;
        this.jsonSupport = jsonSupport;
        this.slowResponseThresholdMs = slowResponseThresholdMs;
    }

    /**
     * 执行白名单动作：按事件 experimentId 条件关闭故障注入。
     * 如果库存服务当前没有活动故障，则记录“已经 inactive”，但仍继续做真实验证。
     */
    public ActionOutcome executeAction(FaultEvent event) {
        Instant now = Instant.now();
        UUID expectedExperimentId = event.getExperimentId();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetExperimentId", expectedExperimentId);
        result.put("checkedAt", now.toString());
        result.put("success", false);

        if (expectedExperimentId == null) {
            return actionFailure(result, "EXPERIMENT_ID_MISSING",
                    "故障事件没有 experimentId，不能执行处置");
        }

        try {
            ExperimentFaultStatusView before = experimentService.status();
            result.put("activeBefore", before.active());
            result.put("observedExperimentIdBefore", before.experimentId());
            result.put("observedFaultTypeBefore", before.faultType());

            if (before.active()) {
                if (!expectedExperimentId.toString().equals(before.experimentId())) {
                    return actionFailure(result, "TARGET_EXPERIMENT_MISMATCH",
                            "库存服务当前活动实验与故障事件不一致，未关闭其他实验");
                }
                ExperimentFaultStatusView after;
                try {
                    // 只调用阶段 1 的条件关闭，不调用无参数 disable()/reset()。
                    after = experimentService.disable(expectedExperimentId);
                } catch (ResponseStatusException ex) {
                    return actionFailure(result, disableFailureCode(ex.getStatusCode()),
                            "条件关闭故障失败: " + safeReason(ex));
                }
                result.put("activeAfter", after.active());
                result.put("observedExperimentIdAfter", after.experimentId());
                if (after.active()) {
                    return actionFailure(result, "FAULT_STILL_ACTIVE",
                            "条件关闭接口返回成功，但库存服务仍报告故障处于 active");
                }
                result.put("success", true);
                result.put("outcome", "DISABLED");
                return new ActionOutcome(true, "DISABLED", null, null, result);
            }

            result.put("activeAfter", false);
            result.put("observedExperimentIdAfter", before.experimentId());
            result.put("success", true);
            result.put("outcome", "ALREADY_INACTIVE");
            return new ActionOutcome(true, "ALREADY_INACTIVE", null, null, result);
        } catch (ResponseStatusException ex) {
            return actionFailure(result, inventoryFailureCode(ex.getStatusCode()),
                    "读取或关闭库存服务故障状态失败: " + safeReason(ex));
        } catch (RuntimeException ex) {
            log.warn("Remediation action failed for event {}", event.getId(), ex);
            return actionFailure(result, "ACTION_EXECUTION_ERROR",
                    "处置动作执行异常: " + ex.getClass().getSimpleName());
        }
    }

    /**
     * 使用故障事件持久化的 sku/quantity 发起一次真实订单结算请求。
     * 每次处置最多验证一次，不自动重试。
     */
    public VerificationOutcome verify(FaultEvent event) {
        VerificationInput input = verificationInput(event);
        if (!input.valid()) {
            Map<String, Object> result = baseVerificationResult(null, null, false);
            result.put("attempted", false);
            result.put("result", "INCONCLUSIVE");
            result.put("verified", false);
            result.put("reason", input.reason());
            return new VerificationOutcome(false, "INCONCLUSIVE", false,
                    "VERIFICATION_INPUT_MISSING", input.reason(), result, null, null);
        }

        ExperimentExecutionView execution;
        try {
            execution = experimentService.checkout(
                    new CheckoutRequest(input.sku(), input.quantity()));
        } catch (RuntimeException ex) {
            log.warn("Remediation verification failed for event {}", event.getId(), ex);
            Map<String, Object> result = baseVerificationResult(
                    input.sku(), input.quantity(), true);
            result.put("attempted", true);
            result.put("result", "INCONCLUSIVE");
            result.put("verified", false);
            result.put("reason", "验证请求执行异常，无法证明恢复");
            result.put("errorType", ex.getClass().getSimpleName());
            String reason = "恢复验证请求执行异常: " + ex.getClass().getSimpleName();
            return new VerificationOutcome(true, "INCONCLUSIVE", false,
                    "VERIFICATION_EXECUTION_ERROR", reason, result, null, null);
        }

        int httpStatus = execution.httpStatus() != null ? execution.httpStatus() : 0;
        boolean pass = httpStatus >= 200 && httpStatus < 300
                && "SUCCESS".equals(execution.outcome())
                && !execution.faultObserved()
                && execution.faultEventId() == null;

        Map<String, Object> result = baseVerificationResult(
                input.sku(), input.quantity(), true);
        result.put("attempted", true);
        result.put("httpStatus", execution.httpStatus());
        result.put("durationMs", execution.durationMs());
        result.put("outcome", execution.outcome());
        result.put("faultObserved", execution.faultObserved());
        result.put("requestId", execution.requestId());
        result.put("faultEventId", execution.faultEventId());

        if (pass) {
            result.put("result", "PASS");
            result.put("verified", true);
            return new VerificationOutcome(true, "PASS", true, null, null,
                    result, execution.requestId(), null);
        }

        boolean failed = execution.faultObserved()
                || httpStatus == 0
                || httpStatus >= 500
                || execution.faultEventId() != null;
        if (failed) {
            String reason = verificationFailureReason(execution, httpStatus);
            String code = "FAULT_STILL_OBSERVED";
            if (httpStatus == 0 || "ORDER_SERVICE_UNREACHABLE".equals(execution.outcome())) {
                code = "ORDER_SERVICE_UNREACHABLE";
            }
            result.put("result", "FAIL");
            result.put("verified", false);
            result.put("reason", reason);
            return new VerificationOutcome(true, "FAIL", false, code, reason,
                    result, execution.requestId(), execution.faultEventId());
        }

        String reason = "验证请求未观察到故障，但业务结果不足以证明恢复";
        String code = httpStatus == 409 || (httpStatus >= 400 && httpStatus < 500)
                ? "BUSINESS_REJECTED" : "VERIFICATION_INCONCLUSIVE";
        result.put("result", "INCONCLUSIVE");
        result.put("verified", false);
        result.put("reason", reason);
        return new VerificationOutcome(true, "INCONCLUSIVE", false, code, reason,
                result, execution.requestId(), execution.faultEventId());
    }

    private VerificationInput verificationInput(FaultEvent event) {
        JsonNode details = jsonSupport.read(event.getDetails());
        if (details == null || details.isNull() || !details.isObject()) {
            return VerificationInput.invalid("故障事件 details 不是有效对象，缺少 sku/quantity");
        }
        JsonNode skuNode = details.get("sku");
        JsonNode quantityNode = details.get("quantity");
        if (skuNode == null || skuNode.isNull() || !skuNode.isTextual()
                || skuNode.asText().isBlank() || skuNode.asText().length() > 64) {
            return VerificationInput.invalid("故障事件 details 缺少合法的 sku");
        }
        if (quantityNode == null || !quantityNode.isIntegralNumber()) {
            return VerificationInput.invalid("故障事件 details 缺少合法的 quantity");
        }
        int quantity = quantityNode.asInt();
        if (quantity < 1 || quantity > 1000) {
            return VerificationInput.invalid("故障事件 details 的 quantity 超出允许范围");
        }
        return VerificationInput.valid(skuNode.asText(), quantity);
    }

    private Map<String, Object> baseVerificationResult(String sku,
                                                       Integer quantity,
                                                       boolean attempted) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("criteriaVersion", CRITERIA_VERSION);
        result.put("sku", sku);
        result.put("quantity", quantity);
        result.put("attempted", attempted);
        result.put("slowThresholdMs", slowResponseThresholdMs);
        result.put("checkedAt", Instant.now().toString());
        return result;
    }

    private ActionOutcome actionFailure(Map<String, Object> result,
                                        String failureCode,
                                        String failureReason) {
        result.put("success", false);
        result.put("outcome", failureCode);
        result.put("failureCode", failureCode);
        result.put("failureReason", failureReason);
        return new ActionOutcome(false, failureCode, failureCode, failureReason, result);
    }

    private String verificationFailureReason(ExperimentExecutionView execution, int httpStatus) {
        if (httpStatus == 0) {
            return "验证请求未获得真实业务响应";
        }
        if (execution.faultObserved()) {
            return "验证请求仍观察到故障: outcome=" + execution.outcome()
                    + ", httpStatus=" + httpStatus;
        }
        if (execution.faultEventId() != null) {
            return "验证请求创建了新的故障事件，不能判定为恢复";
        }
        return "验证请求失败: httpStatus=" + httpStatus
                + ", outcome=" + execution.outcome();
    }

    private String disableFailureCode(HttpStatusCode statusCode) {
        if (statusCode.value() == HttpStatus.CONFLICT.value()) {
            return "FAULT_DISABLE_CONFLICT";
        }
        return inventoryFailureCode(statusCode);
    }

    private String inventoryFailureCode(HttpStatusCode statusCode) {
        if (statusCode.value() == HttpStatus.BAD_GATEWAY.value()
                || statusCode.value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            return "INVENTORY_UNAVAILABLE";
        }
        return "ACTION_EXECUTION_ERROR";
    }

    private String safeReason(ResponseStatusException ex) {
        return ex.getReason() != null ? ex.getReason()
                : "HTTP " + ex.getStatusCode().value();
    }

    public record ActionOutcome(
            boolean success,
            String outcome,
            String failureCode,
            String failureReason,
            Map<String, Object> actionResult) {
    }

    public record VerificationOutcome(
            boolean attempted,
            String result,
            boolean verified,
            String failureCode,
            String failureReason,
            Map<String, Object> verificationResult,
            String requestId,
            UUID faultEventId) {
    }

    private record VerificationInput(boolean valid, String sku, Integer quantity, String reason) {

        static VerificationInput valid(String sku, int quantity) {
            return new VerificationInput(true, sku, quantity, null);
        }

        static VerificationInput invalid(String reason) {
            return new VerificationInput(false, null, null, reason);
        }
    }
}
