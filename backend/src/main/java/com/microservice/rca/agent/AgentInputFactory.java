package com.microservice.rca.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.microservice.rca.common.JsonSupport;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.tools.DiagnosticToolRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 构造 Agent 白名单输入。
 * 只复制观测所需字段；experimentFaultType、experimentParameters、details 等字段不会进入模型输入。
 */
@Component
public class AgentInputFactory {

    private static final List<String> OBSERVED_RESULT_KEYS = List.of(
            "outcome",
            "httpStatus",
            "durationMs",
            "downstreamErrorType",
            "orderStatus",
            "responseMessage",
            "transportError",
            "observedAt");

    private final JsonSupport jsonSupport;
    private final DiagnosticToolRegistry registry;
    private final AgentProperties properties;

    public AgentInputFactory(JsonSupport jsonSupport,
                             DiagnosticToolRegistry registry,
                             AgentProperties properties) {
        this.jsonSupport = jsonSupport;
        this.registry = registry;
        this.properties = properties;
    }

    public AgentOrchestrator.AgentInput create(DiagnosisTask task, FaultEvent event, Instant now) {
        AgentProperties.Limits limits = properties.limits();
        return new AgentOrchestrator.AgentInput(
                event.getId(),
                task.getId(),
                event.getServiceName(),
                event.getEventType(),
                event.getSeverity() != null ? event.getSeverity().name() : null,
                event.getSummary(),
                sanitizeObservedResult(event.getObservedResult()),
                event.getRequestId(),
                event.getOccurredAt() != null ? event.getOccurredAt().minusSeconds(60) : now.minusSeconds(900),
                now,
                registry.definitions(),
                new AgentOrchestrator.Limits(
                        limits.maxModelCalls(),
                        limits.maxToolCalls(),
                        limits.maxOutputChars(),
                        limits.maxPromptChars(),
                        limits.maxToolResultChars(),
                        Duration.ofSeconds(limits.timeoutSeconds())));
    }

    /**
     * 只保留真实观测字段。即使数据库 JSON 中意外混入实验参数，也不会被带进提示词。
     */
    Map<String, Object> sanitizeObservedResult(String observedResultJson) {
        JsonNode node = jsonSupport.read(observedResultJson);
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return sanitized;
        }
        for (String key : OBSERVED_RESULT_KEYS) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                sanitized.put(key, value.numberValue());
            } else if (value.isBoolean()) {
                sanitized.put(key, value.booleanValue());
            } else if (value.isTextual()) {
                sanitized.put(key, value.asText());
            }
        }
        return sanitized;
    }
}
