package com.microservice.rca.tools;

import com.microservice.rca.common.JsonSupport;
import com.microservice.rca.diagnosis.domain.ToolInvocation;
import com.microservice.rca.diagnosis.domain.ToolInvocationStatus;
import com.microservice.rca.diagnosis.repository.ToolInvocationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工具调用审计。每次调用都写入 tool_invocation，并关联真实的诊断任务。
 */
@Service
public class ToolInvocationService {

    private static final int MAX_ERROR_LENGTH = 1024;

    private final DiagnosticToolRegistry registry;
    private final ToolInvocationRepository repository;
    private final JsonSupport jsonSupport;

    public ToolInvocationService(DiagnosticToolRegistry registry,
                                 ToolInvocationRepository repository,
                                 JsonSupport jsonSupport) {
        this.registry = registry;
        this.repository = repository;
        this.jsonSupport = jsonSupport;
    }

    @Transactional
    public ToolInvocationOutcome invoke(UUID diagnosisTaskId,
                                       String toolName,
                                       ToolExecutionContext context,
                                       int sequenceNo) {
        DiagnosticTool tool = registry.require(toolName);
        Instant invokedAt = Instant.now();
        long startedAt = System.nanoTime();

        ToolResult result;
        try {
            result = tool.execute(context);
        } catch (Exception ex) {
            result = ToolResult.failure("工具执行异常: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? " - " + ex.getMessage() : ""));
        }
        int latencyMs = (int) Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        ToolInvocation invocation = new ToolInvocation();
        invocation.setDiagnosisTaskId(diagnosisTaskId);
        invocation.setToolName(toolName);
        invocation.setInputPayload(jsonSupport.write(inputPayload(diagnosisTaskId, context)));
        invocation.setOutputPayload(jsonSupport.write(result.output()));
        invocation.setStatus(result.success() ? ToolInvocationStatus.SUCCEEDED : ToolInvocationStatus.FAILED);
        invocation.setLatencyMs(latencyMs);
        invocation.setErrorMessage(truncate(result.errorMessage()));
        invocation.setInvokedAt(invokedAt);
        invocation.setSequenceNo(sequenceNo);
        ToolInvocation saved = repository.save(invocation);

        return new ToolInvocationOutcome(saved.getId(), toolName, result, latencyMs);
    }

    private Map<String, Object> inputPayload(UUID diagnosisTaskId, ToolExecutionContext context) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("diagnosisTaskId", diagnosisTaskId.toString());
        input.put("serviceName", context.serviceName());
        input.put("windowStart", context.windowStart() != null ? context.windowStart().toString() : null);
        input.put("windowEnd", context.windowEnd() != null ? context.windowEnd().toString() : null);
        input.put("parameters", context.parameters() != null ? context.parameters() : Map.of());
        return input;
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    public record ToolInvocationOutcome(
            UUID invocationId,
            String toolName,
            ToolResult result,
            int latencyMs) {
    }
}
