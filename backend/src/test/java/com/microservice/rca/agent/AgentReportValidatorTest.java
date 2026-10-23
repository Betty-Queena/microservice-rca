package com.microservice.rca.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentReportValidatorTest {

    private final AgentReportValidator validator = new AgentReportValidator();
    private final Set<String> known =
            Set.of("log:order-service:log-1", "metric:inventory-service:2026-10-21T02:00");

    @Test
    void acceptsReportWhoseEvidenceExists() {
        AgentReportValidator.ValidatedReport validated = validator.validate(
                report(List.of("log:order-service:log-1")), known);

        assertThat(validated.confidenceLevel()).isEqualTo("HIGH");
        assertThat(validated.report().get("supportingEvidenceIds"))
                .isEqualTo(List.of("log:order-service:log-1"));
        assertThat(validated.report().get("disclaimer")).asString().contains("人工确认");
    }

    @Test
    void rejectsNonExistentSupportingEvidence() {
        assertThatThrownBy(() -> validator.validate(
                report(List.of("log:order-service:forged-id")), known))
                .isInstanceOf(AgentValidationException.class)
                .hasMessageContaining("不存在的证据");
    }

    @Test
    void rejectsNonExistentContradictingEvidence() {
        Map<String, Object> raw = report(List.of("log:order-service:log-1"));
        raw.put("contradictingEvidenceIds", List.of("metric:inventory-service:forged"));
        assertThatThrownBy(() -> validator.validate(raw, known))
                .isInstanceOf(AgentValidationException.class)
                .hasMessageContaining("不存在的证据");
    }

    @Test
    void rejectsSameEvidenceAsSupportAndContradiction() {
        Map<String, Object> raw = report(List.of("log:order-service:log-1"));
        raw.put("contradictingEvidenceIds", List.of("log:order-service:log-1"));
        assertThatThrownBy(() -> validator.validate(raw, known))
                .isInstanceOf(AgentValidationException.class)
                .hasMessageContaining("不能同时被支持和反驳");
    }

    @Test
    void rejectsUnknownFieldsAndUnsupportedConfidence() {
        Map<String, Object> withUnknown = report(List.of("log:order-service:log-1"));
        withUnknown.put("experimentFaultType", "DELAY");
        assertThatThrownBy(() -> validator.validate(withUnknown, known))
                .isInstanceOf(AgentValidationException.class)
                .hasMessageContaining("未知字段");

        Map<String, Object> withProbability = report(List.of("log:order-service:log-1"));
        withProbability.put("confidenceLevel", "0.93");
        assertThatThrownBy(() -> validator.validate(withProbability, known))
                .isInstanceOf(AgentValidationException.class)
                .hasMessageContaining("confidenceLevel");
    }

    @Test
    void rejectsUnknownAffectedService() {
        Map<String, Object> raw = report(List.of("log:order-service:log-1"));
        raw.put("affectedServices", List.of("database"));
        assertThatThrownBy(() -> validator.validate(raw, known))
                .isInstanceOf(AgentValidationException.class)
                .hasMessageContaining("未知服务");
    }

    private Map<String, Object> report(List<String> supporting) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("rootCause", "库存服务响应延迟导致订单结算超时");
        report.put("affectedServices", List.of("inventory-service"));
        report.put("hypothesis", "库存服务在真实请求中耗时超过订单服务读取超时");
        report.put("supportingEvidenceIds", supporting);
        report.put("contradictingEvidenceIds", List.of());
        report.put("confidenceLevel", "HIGH");
        report.put("recommendedActions", List.of("人工确认后检查库存服务实例负载"));
        report.put("limitations", List.of("当前仅有日志与分钟级指标，没有调用链数据"));
        return report;
    }
}
