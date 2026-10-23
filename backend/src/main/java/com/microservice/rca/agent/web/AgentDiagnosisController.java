package com.microservice.rca.agent.web;

import com.microservice.rca.agent.AgentDiagnosisService;
import com.microservice.rca.agent.api.AgentTaskDetailView;
import com.microservice.rca.agent.api.AgentTaskSummaryView;
import com.microservice.rca.common.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM Agent 诊断任务 API。创建后立即返回 PENDING 任务，后台异步执行。
 */
@RestController
@RequestMapping("/api")
public class AgentDiagnosisController {

    private final AgentDiagnosisService diagnosisService;

    public AgentDiagnosisController(AgentDiagnosisService diagnosisService) {
        this.diagnosisService = diagnosisService;
    }

    @PostMapping("/fault-events/{eventId}/diagnosis")
    public ApiResponse<AgentTaskDetailView> start(@PathVariable UUID eventId) {
        return ApiResponse.ok(diagnosisService.startForEvent(eventId));
    }

    @GetMapping("/diagnosis/tasks/{taskId}")
    public ApiResponse<AgentTaskDetailView> detail(@PathVariable UUID taskId) {
        return ApiResponse.ok(diagnosisService.detail(taskId));
    }

    @GetMapping("/diagnosis/tasks")
    public ApiResponse<List<AgentTaskSummaryView>> list(@RequestParam UUID faultEventId) {
        return ApiResponse.ok(diagnosisService.listForEvent(faultEventId));
    }

    @PostMapping("/diagnosis/tasks/{taskId}/retry")
    public ApiResponse<AgentTaskDetailView> retry(@PathVariable UUID taskId) {
        return ApiResponse.ok(diagnosisService.retry(taskId));
    }
}
