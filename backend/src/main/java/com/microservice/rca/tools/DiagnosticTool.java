package com.microservice.rca.tools;

import java.util.Map;

/**
 * 诊断工具契约。后续实现必须满足：只读优先、参数校验、超时控制、调用审计。
 */
public interface DiagnosticTool {

    String name();

    String description();

    ToolResult execute(ToolExecutionContext context);
}
