package com.microservice.rca.diagnosis.web;

import com.microservice.rca.common.ApiResponse;
import com.microservice.rca.tools.DiagnosticToolRegistry;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 诊断工具元数据与未来 Agent 统一入口说明。
 * 本轮不实现 LLM 调用；Agent 诊断状态在前端明确标记为“待开发”。
 */
@RestController
@RequestMapping("/api/diagnosis")
public class DiagnosisToolController {

    private final DiagnosticToolRegistry registry;

    public DiagnosisToolController(DiagnosticToolRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/tools")
    public ApiResponse<List<DiagnosticToolRegistry.ToolDefinition>> tools() {
        return ApiResponse.ok(registry.definitions());
    }
}
