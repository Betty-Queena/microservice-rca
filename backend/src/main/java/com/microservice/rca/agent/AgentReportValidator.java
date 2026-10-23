package com.microservice.rca.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 最终报告校验。关键约束：报告引用的 evidenceId 必须来自实际工具结果。
 */
@Component
public class AgentReportValidator {

    private static final Set<String> REQUIRED_KEYS = Set.of(
            "rootCause", "affectedServices", "hypothesis", "supportingEvidenceIds",
            "contradictingEvidenceIds", "confidenceLevel", "recommendedActions", "limitations");
    private static final Set<String> CONFIDENCE_LEVELS = Set.of("HIGH", "MEDIUM", "LOW");
    private static final Set<String> KNOWN_SERVICES = Set.of("order-service", "inventory-service");

    public ValidatedReport validate(Map<String, Object> raw, Set<String> knownEvidenceIds) {
        if (raw == null) {
            throw new AgentValidationException("最终报告为空");
        }
        for (String key : raw.keySet()) {
            if (!REQUIRED_KEYS.contains(key)) {
                throw new AgentValidationException("最终报告包含未知字段: " + key);
            }
        }

        String rootCause = requiredText(raw, "rootCause", 3, 1024);
        String hypothesis = requiredText(raw, "hypothesis", 3, 1024);
        String confidenceLevel = requiredText(raw, "confidenceLevel", 1, 16);
        if (!CONFIDENCE_LEVELS.contains(confidenceLevel)) {
            throw new AgentValidationException("confidenceLevel 只能是 HIGH/MEDIUM/LOW");
        }

        List<String> affectedServices = stringList(raw, "affectedServices", 1, 2);
        for (String service : affectedServices) {
            if (!KNOWN_SERVICES.contains(service)) {
                throw new AgentValidationException("affectedServices 包含未知服务: " + service);
            }
        }
        if (affectedServices.stream().distinct().count() != affectedServices.size()) {
            throw new AgentValidationException("affectedServices 不能重复");
        }

        List<String> supporting = stringList(raw, "supportingEvidenceIds", 1, 100);
        List<String> contradicting = stringList(raw, "contradictingEvidenceIds", 0, 100);
        List<String> recommendations = stringList(raw, "recommendedActions", 0, 10);
        List<String> limitations = stringList(raw, "limitations", 0, 10);

        for (String evidenceId : supporting) {
            if (!knownEvidenceIds.contains(evidenceId)) {
                throw new AgentValidationException("supportingEvidenceIds 引用了不存在的证据: " + evidenceId);
            }
        }
        for (String evidenceId : contradicting) {
            if (!knownEvidenceIds.contains(evidenceId)) {
                throw new AgentValidationException("contradictingEvidenceIds 引用了不存在的证据: " + evidenceId);
            }
        }
        Set<String> supportingSet = new LinkedHashSet<>(supporting);
        for (String evidenceId : contradicting) {
            if (supportingSet.contains(evidenceId)) {
                throw new AgentValidationException("同一证据不能同时被支持和反驳: " + evidenceId);
            }
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("rootCause", rootCause);
        normalized.put("affectedServices", affectedServices);
        normalized.put("hypothesis", hypothesis);
        normalized.put("supportingEvidenceIds", supporting);
        normalized.put("contradictingEvidenceIds", contradicting);
        normalized.put("confidenceLevel", confidenceLevel);
        normalized.put("recommendedActions", recommendations);
        normalized.put("limitations", limitations);
        normalized.put("disclaimer", "诊断结论由 LLM 基于真实工具证据生成，仅供人工确认，不自动执行处置");
        return new ValidatedReport(normalized, confidenceLevel);
    }

    private String requiredText(Map<String, Object> raw, String key, int minLength, int maxLength) {
        Object value = raw.get(key);
        if (!(value instanceof String text)) {
            throw new AgentValidationException("最终报告字段 " + key + " 必须是字符串");
        }
        String trimmed = text.trim();
        if (trimmed.length() < minLength) {
            throw new AgentValidationException("最终报告字段 " + key + " 过短");
        }
        if (trimmed.length() > maxLength) {
            throw new AgentValidationException("最终报告字段 " + key + " 超过 " + maxLength + " 字符");
        }
        return trimmed;
    }

    private List<String> stringList(Map<String, Object> raw, String key, int minSize, int maxSize) {
        Object value = raw.get(key);
        if (!(value instanceof List<?> list)) {
            throw new AgentValidationException("最终报告字段 " + key + " 必须是数组");
        }
        if (list.size() < minSize || list.size() > maxSize) {
            throw new AgentValidationException("最终报告字段 " + key + " 数量必须在 "
                    + minSize + "-" + maxSize + " 之间");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new AgentValidationException("最终报告字段 " + key + " 只能包含非空字符串");
            }
            String trimmed = text.trim();
            if (trimmed.length() > 512) {
                throw new AgentValidationException("最终报告字段 " + key + " 的条目超过 512 字符");
            }
            result.add(trimmed);
        }
        return result;
    }

    public record ValidatedReport(Map<String, Object> report, String confidenceLevel) {
    }
}
