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

    /**
     * 失败结果也可以携带真实探测明细（例如连接被拒绝的连通性证据）。
     * 工具仍标记为失败，不会把探测失败伪装成成功的健康或指标结果。
     */
    public static ToolResult failure(String errorMessage, Map<String, Object> output) {
        return new ToolResult(false, output == null ? Map.of() : output, errorMessage);
    }
}
