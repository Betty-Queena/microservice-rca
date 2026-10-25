package com.microservice.rca.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.rca.common.JsonSupport;
import com.microservice.rca.diagnosis.domain.ToolInvocation;
import com.microservice.rca.diagnosis.repository.ToolInvocationRepository;
import com.microservice.rca.external.service.ExternalServiceRegistry;
import com.microservice.rca.tools.DiagnosticTool;
import com.microservice.rca.tools.DiagnosticToolRegistry;
import com.microservice.rca.tools.ToolInvocationService;
import com.microservice.rca.tools.ToolResult;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class AgentTestSupport {

    private AgentTestSupport() {
    }

    static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    static JsonSupport json() {
        return new JsonSupport(mapper());
    }

    static AgentProperties properties(int maxModelCalls, int maxToolCalls) {
        return new AgentProperties(
                true,
                new AgentProperties.DeepSeek("test-key", "http://localhost", "mock-deepseek",
                        0.1, 500, 1000, 5000),
                new AgentProperties.Limits(maxModelCalls, maxToolCalls,
                        12000, 60000, 6000, 60));
    }

    static DiagnosticTool tool(String name, ToolResult result) {
        return new DiagnosticTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name + " test tool";
            }

            @Override
            public ToolResult execute(com.microservice.rca.tools.ToolExecutionContext context) {
                return result;
            }
        };
    }

    static DiagnosticToolRegistry registry(DiagnosticTool... tools) {
        return new DiagnosticToolRegistry(List.of(tools));
    }

    static ExternalServiceRegistry externalServiceRegistry() {
        ExternalServiceRegistry registry = mock(ExternalServiceRegistry.class);
        when(registry.findByName(any(String.class))).thenReturn(java.util.Optional.empty());
        return registry;
    }

    static ToolInvocationRepository invocationRepository() {
        ToolInvocationRepository repository = mock(ToolInvocationRepository.class);
        when(repository.save(any(ToolInvocation.class))).thenAnswer(invocation -> {
            ToolInvocation entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            return entity;
        });
        return repository;
    }

    static ToolInvocationService invocationService(DiagnosticToolRegistry registry,
                                                   ToolInvocationRepository repository) {
        return new ToolInvocationService(registry, repository, json());
    }

    static AgentOrchestrator.AgentInput input(DiagnosticToolRegistry registry,
                                              int maxModelCalls,
                                              int maxToolCalls) {
        return input(registry, maxModelCalls, maxToolCalls, java.time.Duration.ofSeconds(60));
    }

    static AgentOrchestrator.AgentInput input(DiagnosticToolRegistry registry,
                                              int maxModelCalls,
                                              int maxToolCalls,
                                              java.time.Duration overallTimeout) {
        Instant end = Instant.parse("2026-10-21T02:00:00Z");
        return new AgentOrchestrator.AgentInput(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "inventory-service",
                "BUILT_IN",
                List.of("all", "order-service", "inventory-service"),
                "DOWNSTREAM_TIMEOUT",
                "ERROR",
                "订单结算调用库存服务超时",
                Map.of(
                        "outcome", "DOWNSTREAM_TIMEOUT",
                        "httpStatus", 502,
                        "durationMs", 5410,
                        "downstreamErrorType", "TIMEOUT"),
                "exp-abc123",
                end.minusSeconds(900),
                end,
                registry.definitions(),
                Map.of(),
                new AgentOrchestrator.Limits(maxModelCalls, maxToolCalls,
                        12000, 60000, 6000, overallTimeout));
    }

    static AgentOrchestrator.AgentInput externalInput(DiagnosticToolRegistry registry,
                                                      String serviceName,
                                                      int maxModelCalls,
                                                      int maxToolCalls) {
        return externalInput(registry, serviceName, maxModelCalls, maxToolCalls,
                Map.of(), java.time.Duration.ofSeconds(60));
    }

    static AgentOrchestrator.AgentInput externalInput(DiagnosticToolRegistry registry,
                                                      String serviceName,
                                                      int maxModelCalls,
                                                      int maxToolCalls,
                                                      java.time.Duration overallTimeout) {
        return externalInput(registry, serviceName, maxModelCalls, maxToolCalls,
                Map.of(), overallTimeout);
    }

    static AgentOrchestrator.AgentInput externalInput(DiagnosticToolRegistry registry,
                                                      String serviceName,
                                                      int maxModelCalls,
                                                      int maxToolCalls,
                                                      Map<String, String> unavailableTools,
                                                      java.time.Duration overallTimeout) {
        Instant end = Instant.parse("2026-10-21T02:00:00Z");
        return new AgentOrchestrator.AgentInput(
                UUID.randomUUID(),
                UUID.randomUUID(),
                serviceName,
                "EXTERNAL",
                List.of(serviceName),
                "EXTERNAL_ANOMALY",
                "WARNING",
                "外部服务 " + serviceName + " 运行状态需要诊断",
                Map.of(),
                null,
                end.minusSeconds(900),
                end,
                registry.definitions(),
                unavailableTools,
                new AgentOrchestrator.Limits(maxModelCalls, maxToolCalls,
                        12000, 60000, 6000, overallTimeout));
    }

    static String callTool(String tool, Map<String, Object> arguments) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "CALL_TOOL");
        payload.put("tool", tool);
        payload.put("arguments", arguments);
        payload.put("reason", "测试决策");
        return json().write(payload);
    }

    static String finalReport(List<String> supportingEvidenceIds) {
        return finalReport(supportingEvidenceIds, "HIGH", "inventory-service");
    }

    static String finalReport(List<String> supportingEvidenceIds, String confidenceLevel) {
        return finalReport(supportingEvidenceIds, confidenceLevel, "inventory-service");
    }

    static String finalReport(List<String> supportingEvidenceIds,
                              String confidenceLevel,
                              String affectedService) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("rootCause", "库存服务响应延迟导致订单结算超时");
        report.put("affectedServices", List.of(affectedService));
        report.put("hypothesis", "库存服务在真实请求中耗时超过订单服务读取超时");
        report.put("supportingEvidenceIds", supportingEvidenceIds);
        report.put("contradictingEvidenceIds", List.of());
        report.put("confidenceLevel", confidenceLevel);
        report.put("recommendedActions", List.of("人工确认后检查库存服务实例负载"));
        report.put("limitations", List.of("当前仅有日志与分钟级指标，没有调用链数据"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "FINAL_REPORT");
        payload.put("report", report);
        payload.put("reason", "已有真实日志与指标证据");
        return json().write(payload);
    }

    static String insufficientEvidence() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "INSUFFICIENT_EVIDENCE");
        payload.put("reason", "工具未返回足以区分根因的证据");
        payload.put("limitations", List.of("缺少调用链与配置变更数据"));
        return json().write(payload);
    }

    static Map<String, Object> logsOutput() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("tool", "query_logs");
        output.put("dataSources", List.of("order-service:/api/observability/logs"));
        output.put("evidenceIds", List.of("log:order-service:log-1", "log:inventory-service:log-2"));
        output.put("items", List.of(Map.of("id", "log-1", "status", 502, "requestId", "exp-abc123")));
        output.put("warnings", List.of());
        return output;
    }

    static Map<String, Object> metricsOutput() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("tool", "query_metrics");
        output.put("dataSources", List.of("inventory-service:/api/observability/metrics"));
        output.put("evidenceIds", List.of("metric:inventory-service:2026-10-21T02:00"));
        output.put("summary", Map.of("requestCount", 1, "serverErrorCount", 0));
        output.put("warnings", List.of());
        return output;
    }

    static final class ScriptedModelClient implements AgentModelClient {

        private final Deque<Object> script = new ArrayDeque<>();
        private final List<ModelRequest> requests = new ArrayList<>();
        private final boolean configured;

        ScriptedModelClient(boolean configured, Object... responses) {
            this.configured = configured;
            for (Object response : responses) {
                script.add(response);
            }
        }

        @Override
        public String modelName() {
            return "mock-deepseek";
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public ModelResponse complete(ModelRequest request) {
            requests.add(request);
            Object next = script.poll();
            if (next == null) {
                throw new IllegalStateException("测试脚本已耗尽");
            }
            if (next instanceof RuntimeException exception) {
                throw exception;
            }
            if (next instanceof ModelResponse response) {
                return response;
            }
            return new ModelResponse((String) next, modelName(), 12, 8, true, null);
        }

        List<ModelRequest> requests() {
            return requests;
        }
    }
}
