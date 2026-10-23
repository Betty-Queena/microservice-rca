package com.microservice.rca.tools;

import java.util.Map;

public record ToolResult(
        boolean success,
        Map<String, Object> output,
        String errorMessage) {

    public static ToolResult success(Map<String, Object> output) {
        return new ToolResult(true, output, null);
    }

    public static ToolResult failure(String errorMessage) {
        return new ToolResult(false, Map.of(), errorMessage);
    }
}
