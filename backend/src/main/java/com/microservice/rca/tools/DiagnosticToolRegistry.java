package com.microservice.rca.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 诊断工具注册表。Agent 接入后通过统一入口按名称调用工具。
 */
@Component
public class DiagnosticToolRegistry {

    private final Map<String, DiagnosticTool> tools;

    public DiagnosticToolRegistry(List<DiagnosticTool> toolList) {
        Map<String, DiagnosticTool> registered = new LinkedHashMap<>();
        for (DiagnosticTool tool : toolList) {
            registered.put(tool.name(), tool);
        }
        this.tools = Map.copyOf(registered);
    }

    public DiagnosticTool require(String name) {
        DiagnosticTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未知诊断工具: " + name + "，可用工具: " + tools.keySet());
        }
        return tool;
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream()
                .map(tool -> new ToolDefinition(tool.name(), tool.description()))
                .toList();
    }

    public record ToolDefinition(String name, String description) {
    }
}
