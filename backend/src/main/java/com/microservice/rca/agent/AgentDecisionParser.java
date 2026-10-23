package com.microservice.rca.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 解析并校验模型输出的单个 JSON 决策。
 * 解析失败会转成 AgentValidationException，作为反馈进入下一轮，而不是伪造工具调用。
 */
@Component
public class AgentDecisionParser {

    private final ObjectMapper objectMapper;

    public AgentDecisionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Decision parse(String content, int maxOutputChars) {
        if (content == null || content.isBlank()) {
            throw new AgentValidationException("模型输出为空");
        }
        if (content.length() > maxOutputChars) {
            throw new AgentValidationException("模型输出超过长度限制: " + content.length()
                    + " > " + maxOutputChars);
        }
        JsonNode root = readJson(content);
        if (!root.isObject()) {
            throw new AgentValidationException("模型输出必须是 JSON 对象");
        }
        String action = text(root, "action");
        if (action == null) {
            throw new AgentValidationException("模型输出缺少 action");
        }
        return switch (action) {
            case "CALL_TOOL" -> parseCallTool(root);
            case "FINAL_REPORT" -> parseFinalReport(root);
            case "INSUFFICIENT_EVIDENCE" -> parseInsufficient(root);
            default -> throw new AgentValidationException("不支持的 action: " + action);
        };
    }

    private Decision parseCallTool(JsonNode root) {
        String tool = text(root, "tool");
        if (tool == null || tool.isBlank()) {
            throw new AgentValidationException("CALL_TOOL 缺少 tool");
        }
        JsonNode argumentsNode = root.get("arguments");
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (argumentsNode != null && !argumentsNode.isNull()) {
            if (!argumentsNode.isObject()) {
                throw new AgentValidationException("CALL_TOOL.arguments 必须是 JSON 对象");
            }
            argumentsNode.fields().forEachRemaining(entry ->
                    arguments.put(entry.getKey(), toJavaValue(entry.getValue())));
        }
        return new CallTool(tool.trim(), arguments, text(root, "reason"));
    }

    private Decision parseFinalReport(JsonNode root) {
        JsonNode reportNode = root.get("report");
        if (reportNode == null || !reportNode.isObject()) {
            throw new AgentValidationException("FINAL_REPORT 缺少 report 对象");
        }
        Map<String, Object> report = new LinkedHashMap<>();
        reportNode.fields().forEachRemaining(entry ->
                report.put(entry.getKey(), toJavaValue(entry.getValue())));
        return new FinalReport(report, text(root, "reason"));
    }

    private Decision parseInsufficient(JsonNode root) {
        String reason = text(root, "reason");
        if (reason == null || reason.isBlank()) {
            throw new AgentValidationException("INSUFFICIENT_EVIDENCE 缺少 reason");
        }
        List<String> limitations = stringList(root.get("limitations"));
        return new InsufficientEvidence(reason, limitations);
    }

    private JsonNode readJson(String content) {
        String candidate = stripFences(content.trim());
        int firstBrace = candidate.indexOf('{');
        int lastBrace = candidate.lastIndexOf('}');
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            throw new AgentValidationException("模型输出中没有 JSON 对象");
        }
        candidate = candidate.substring(firstBrace, lastBrace + 1);
        try {
            return objectMapper.readTree(candidate);
        } catch (JsonProcessingException ex) {
            throw new AgentValidationException("模型输出 JSON 无法解析: " + ex.getOriginalMessage());
        }
    }

    private String stripFences(String content) {
        if (!content.startsWith("```")) {
            return content;
        }
        int firstNewline = content.indexOf('\n');
        int lastFence = content.lastIndexOf("```");
        if (firstNewline >= 0 && lastFence > firstNewline) {
            return content.substring(firstNewline + 1, lastFence).trim();
        }
        return content;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        return value.asText();
    }

    private List<String> stringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            }
        }
        return result;
    }

    private Object toJavaValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isIntegralNumber()) {
            return value.longValue();
        }
        if (value.isNumber()) {
            return value.doubleValue();
        }
        if (value.isArray()) {
            List<Object> list = new ArrayList<>();
            value.forEach(item -> list.add(toJavaValue(item)));
            return list;
        }
        if (value.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            value.fields().forEachRemaining(entry -> map.put(entry.getKey(), toJavaValue(entry.getValue())));
            return map;
        }
        return value.asText();
    }

    public sealed interface Decision permits CallTool, FinalReport, InsufficientEvidence {
    }

    public record CallTool(String tool, Map<String, Object> arguments, String reason) implements Decision {
    }

    public record FinalReport(Map<String, Object> report, String reason) implements Decision {
    }

    public record InsufficientEvidence(String reason, List<String> limitations) implements Decision {
    }
}
