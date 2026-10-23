package com.microservice.rca.agent;

import static com.microservice.rca.agent.AgentTestSupport.callTool;
import static com.microservice.rca.agent.AgentTestSupport.finalReport;
import static com.microservice.rca.agent.AgentTestSupport.input;
import static com.microservice.rca.agent.AgentTestSupport.insufficientEvidence;
import static com.microservice.rca.agent.AgentTestSupport.invocationService;
import static com.microservice.rca.agent.AgentTestSupport.logsOutput;
import static com.microservice.rca.agent.AgentTestSupport.mapper;
import static com.microservice.rca.agent.AgentTestSupport.metricsOutput;
import static com.microservice.rca.agent.AgentTestSupport.properties;
import static com.microservice.rca.agent.AgentTestSupport.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.microservice.rca.agent.AgentOrchestrator.AgentInput;
import com.microservice.rca.agent.AgentOrchestrator.AgentRunResult;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.domain.FaultSeverity;
import com.microservice.rca.diagnosis.domain.ToolInvocation;
import com.microservice.rca.diagnosis.repository.ToolInvocationRepository;
import com.microservice.rca.tools.DiagnosticTool;
import com.microservice.rca.tools.DiagnosticToolRegistry;
import com.microservice.rca.tools.ToolExecutionContext;
import com.microservice.rca.tools.ToolResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultAgentOrchestratorTest {

    @Test
    void executesQueryLogsChosenByModel() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())),
                tool("query_metrics", ToolResult.success(metricsOutput())));
        ToolInvocationRepository repository = AgentTestSupport.invocationRepository();
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_logs", Map.of("service", "all", "requestId", "exp-abc123", "limit", 20)),
                finalReport(List.of("log:order-service:log-1")));

        AgentRunResult result = orchestrator(model, registry, repository, 4, 3)
                .diagnose(input(registry, 4, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.ROOT_CAUSE_IDENTIFIED);
        assertThat(result.report().get("rootCause")).asString().contains("库存服务");
        assertThat(result.confidenceLevel()).isEqualTo("HIGH");
        assertThat(model.requests()).hasSize(2);
        assertThat(model.requests().get(0).userPrompt()).contains("query_logs");
        verify(repository).save(any(ToolInvocation.class));
    }

    @Test
    void executesQueryMetricsChosenByModel() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())),
                tool("query_metrics", ToolResult.success(metricsOutput())));
        ToolInvocationRepository repository = AgentTestSupport.invocationRepository();
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_metrics", Map.of("service", "inventory-service", "lookbackMinutes", 15)),
                finalReport(List.of("metric:inventory-service:2026-10-21T02:00")));

        AgentRunResult result = orchestrator(model, registry, repository, 4, 3)
                .diagnose(input(registry, 4, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.ROOT_CAUSE_IDENTIFIED);
        assertThat(result.decisionTrace()).anySatisfy(entry ->
                assertThat(entry.get("type")).isEqualTo("TOOL_EXECUTION"));
        assertThat(model.requests()).hasSize(2);
    }

    @Test
    void supportsMultiRoundInvestigation() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())),
                tool("query_metrics", ToolResult.success(metricsOutput())));
        ToolInvocationRepository repository = AgentTestSupport.invocationRepository();
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_logs", Map.of("service", "all", "limit", 20)),
                callTool("query_metrics", Map.of("service", "all", "lookbackMinutes", 10)),
                finalReport(List.of(
                        "log:order-service:log-1",
                        "metric:inventory-service:2026-10-21T02:00")));

        AgentRunResult result = orchestrator(model, registry, repository, 5, 4)
                .diagnose(input(registry, 5, 4));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.ROOT_CAUSE_IDENTIFIED);
        assertThat(model.requests()).hasSize(3);
        verify(repository, org.mockito.Mockito.times(2)).save(any(ToolInvocation.class));
        // 每一轮模型调用（包括被接受并执行工具的轮次）都应记录 usage 与耗时。
        assertThat(result.decisionTrace()).filteredOn(entry ->
                        "MODEL_DECISION".equals(entry.get("type")))
                .hasSize(3)
                .allSatisfy(entry -> {
                    assertThat(entry.get("usageAvailable")).isEqualTo(true);
                    assertThat(entry.get("promptTokens")).isEqualTo(12);
                    assertThat(entry.get("completionTokens")).isEqualTo(8);
                });
        assertThat(result.decisionTrace()).filteredOn(entry ->
                        "MODEL_DECISION".equals(entry.get("type")))
                .anySatisfy(entry -> assertThat(entry.get("decision")).isEqualTo("CALL_TOOL"));
    }

    @Test
    void rejectsUnregisteredToolBeforeAnyExecution() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        ToolInvocationRepository repository = AgentTestSupport.invocationRepository();
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("run_shell", Map.of("command", "cat /etc/passwd")),
                insufficientEvidence());

        AgentRunResult result = orchestrator(model, registry, repository, 3, 3)
                .diagnose(input(registry, 3, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.decisionTrace()).anySatisfy(entry -> {
            assertThat(entry.get("type")).isEqualTo("TOOL_REJECTED");
            assertThat(String.valueOf(entry.get("error"))).contains("未注册的工具");
        });
        verify(repository, never()).save(any(ToolInvocation.class));
    }

    @Test
    void rejectsNonExistentEvidenceAndNeverReturnsItAsRootCause() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())),
                tool("query_metrics", ToolResult.success(metricsOutput())));
        ToolInvocationRepository repository = AgentTestSupport.invocationRepository();
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_logs", Map.of("service", "all", "limit", 20)),
                finalReport(List.of("log:order-service:forged-id")),
                insufficientEvidence());

        AgentRunResult result = orchestrator(model, registry, repository, 4, 3)
                .diagnose(input(registry, 4, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.report().toString()).doesNotContain("forged-id");
        assertThat(result.decisionTrace()).anySatisfy(entry -> {
            assertThat(entry.get("type")).isEqualTo("MODEL_DECISION");
            assertThat(entry.get("decision")).isEqualTo("FINAL_REPORT_REJECTED");
            assertThat(String.valueOf(entry.get("validationError"))).contains("不存在的证据");
        });
    }

    @Test
    void returnsInsufficientEvidenceWithoutRootCause() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true, insufficientEvidence());

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 2, 2)
                .diagnose(input(registry, 2, 2));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.report()).doesNotContainKey("rootCause");
        assertThat(result.confidenceLevel()).isNull();
    }

    @Test
    void recordsConfigurationFailureWhenKeyIsMissing() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(false);

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 2, 2)
                .diagnose(input(registry, 2, 2));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.FAILED);
        assertThat(result.failureReason()).contains("DEEPSEEK_API_KEY");
        assertThat(result.report()).isNull();
        assertThat(result.decisionTrace()).anySatisfy(entry -> {
            assertThat(entry.get("status")).isEqualTo("FAILED");
            assertThat(entry.get("errorType")).isEqualTo("CONFIG_MISSING");
            assertThat(entry.get("modelCallAttempted")).isEqualTo(false);
        });
        assertThat(model.requests()).isEmpty();
    }

    @Test
    void handlesModelFailureWithoutFabricatingResult() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true, new AgentModelException("TIMEOUT", "DeepSeek 调用超时", true));

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 1, 2)
                .diagnose(input(registry, 1, 2));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.FAILED);
        assertThat(result.failureReason()).contains("超时");
        assertThat(result.report()).isNull();
        assertThat(result.decisionTrace()).anySatisfy(entry -> {
            assertThat(entry.get("type")).isEqualTo("MODEL_DECISION");
            assertThat(entry.get("errorType")).isEqualTo("TIMEOUT");
        });
    }

    @Test
    void passesRemainingBudgetToModelAndToolCalls() {
        AtomicReference<Duration> toolTimeout = new AtomicReference<>();
        DiagnosticTool capturingTool = new DiagnosticTool() {
            @Override
            public String name() {
                return "query_logs";
            }

            @Override
            public String description() {
                return "capture tool timeout";
            }

            @Override
            public ToolResult execute(ToolExecutionContext context) {
                toolTimeout.set(context.timeout());
                return ToolResult.success(logsOutput());
            }
        };
        DiagnosticToolRegistry registry = AgentTestSupport.registry(capturingTool);
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_logs", Map.of("service", "all", "limit", 20)),
                insufficientEvidence());

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 3, 2)
                .diagnose(input(registry, 3, 2, Duration.ofSeconds(60)));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.INSUFFICIENT_EVIDENCE);
        Duration modelTimeout = model.requests().get(0).timeout();
        assertThat(modelTimeout).isNotNull();
        assertThat(modelTimeout).isPositive();
        assertThat(modelTimeout).isLessThanOrEqualTo(Duration.ofSeconds(60));
        assertThat(toolTimeout.get()).isNotNull();
        assertThat(toolTimeout.get()).isPositive();
        assertThat(toolTimeout.get()).isLessThanOrEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void stopsImmediatelyWhenOverallBudgetIsAlreadyExhausted() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true, insufficientEvidence());

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 3, 2)
                .diagnose(input(registry, 3, 2, Duration.ZERO));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.FAILED);
        assertThat(result.failureReason()).contains("超时");
        assertThat(model.requests()).isEmpty();
    }

    @Test
    void recordsToolTimeoutWhenBudgetIsExhaustedAfterModelDecision() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        ToolInvocationRepository repository = AgentTestSupport.invocationRepository();
        AgentModelClient slowModel = new AgentModelClient() {
            @Override
            public String modelName() {
                return "slow-model";
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public AgentModelClient.ModelResponse complete(AgentModelClient.ModelRequest request) {
                try {
                    Thread.sleep(120L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return new AgentModelClient.ModelResponse(
                        callTool("query_logs", Map.of("service", "all", "limit", 20)),
                        modelName(), 12, 8, true);
            }
        };

        AgentRunResult result = orchestrator(slowModel, registry, repository, 3, 2)
                .diagnose(input(registry, 3, 2, Duration.ofMillis(80)));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.FAILED);
        assertThat(result.failureReason()).contains("超时");
        assertThat(result.decisionTrace()).anySatisfy(entry -> {
            assertThat(entry.get("type")).isEqualTo("TOOL_EXECUTION");
            assertThat(entry.get("status")).isEqualTo("TIMEOUT");
            assertThat(entry.get("errorType")).isEqualTo("TOOL_TIMEOUT");
        });
        verify(repository, never()).save(any(ToolInvocation.class));
    }

    @Test
    void handlesToolFailureAndDoesNotTurnItIntoRootCause() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.failure("观测服务不可达")));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_logs", Map.of("service", "all", "limit", 20)),
                insufficientEvidence());

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 3, 2)
                .diagnose(input(registry, 3, 2));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.decisionTrace()).anySatisfy(entry -> {
            assertThat(entry.get("type")).isEqualTo("TOOL_EXECUTION");
            assertThat(entry.get("status")).isEqualTo("FAILED");
        });
        assertThat(result.report()).doesNotContainKey("rootCause");
    }

    @Test
    void stopsWhenModelCallLimitIsReached() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_logs", Map.of("service", "all", "limit", 20)),
                finalReport(List.of("log:order-service:log-1")));

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 1, 3)
                .diagnose(input(registry, 1, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.FAILED);
        assertThat(result.failureReason()).contains("最大模型调用轮数");
        assertThat(model.requests()).hasSize(1);
    }

    @Test
    void skipsDuplicateToolCall() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_logs", Map.of("service", "all", "limit", 20)),
                callTool("query_logs", Map.of("service", "all", "limit", 20)),
                insufficientEvidence());

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 4, 4)
                .diagnose(input(registry, 4, 4));

        assertThat(result.decisionTrace()).anySatisfy(entry -> {
            assertThat(entry.get("type")).isEqualTo("TOOL_REJECTED");
            assertThat(String.valueOf(entry.get("error"))).contains("重复");
        });
        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void modelInputDoesNotContainExperimentPresetFields() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentInputFactory factory = new AgentInputFactory(
                AgentTestSupport.json(), registry, properties(3, 2));
        DiagnosisTask task = new DiagnosisTask();
        task.setId(UUID.randomUUID());
        task.setFaultEventId(UUID.randomUUID());

        FaultEvent event = new FaultEvent();
        event.setId(task.getFaultEventId());
        event.setServiceName("inventory-service");
        event.setEventType("DOWNSTREAM_TIMEOUT");
        event.setSeverity(FaultSeverity.ERROR);
        event.setSummary("订单结算调用库存服务超时");
        event.setOccurredAt(Instant.now().minusSeconds(30));
        event.setRequestId("exp-sanitized");
        event.setExperimentFaultType("DELAY");
        event.setExperimentParameters("{\"delayMs\":3500}");
        event.setObservedResult("{\"outcome\":\"DOWNSTREAM_TIMEOUT\",\"httpStatus\":502,"
                + "\"durationMs\":5410,\"experimentFaultType\":\"DELAY\"}");

        AgentInput input = factory.create(task, event, Instant.now());
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true, insufficientEvidence());

        orchestrator(model, registry, AgentTestSupport.invocationRepository(), 3, 2)
                .diagnose(input);

        String prompt = model.requests().get(0).userPrompt();
        assertThat(prompt).doesNotContain("experimentFaultType");
        assertThat(prompt).doesNotContain("experimentParameters");
        assertThat(prompt).doesNotContain("DELAY");
        assertThat(prompt).doesNotContain("3500");
    }

    private DefaultAgentOrchestrator orchestrator(AgentModelClient model,
                                                  DiagnosticToolRegistry registry,
                                                  ToolInvocationRepository repository,
                                                  int maxModelCalls,
                                                  int maxToolCalls) {
        AgentProperties properties = properties(maxModelCalls, maxToolCalls);
        return new DefaultAgentOrchestrator(
                model,
                new AgentPromptBuilder(AgentTestSupport.json()),
                new AgentDecisionParser(mapper()),
                new AgentToolCallValidator(),
                new AgentReportValidator(),
                invocationService(registry, repository),
                properties);
    }
}
