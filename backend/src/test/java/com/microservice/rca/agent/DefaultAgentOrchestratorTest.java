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
import org.springframework.web.client.RestClient;

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
    void normalRequestWithoutEvidenceDoesNotFabricateRootCause() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(Map.of(
                        "tool", "query_logs",
                        "dataSources", List.of("order-service:/api/observability/logs"),
                        "evidenceIds", List.of(),
                        "items", List.of(),
                        "warnings", List.of()))));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_logs", Map.of("service", "all", "limit", 20)),
                insufficientEvidence());

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 3, 2)
                .diagnose(input(registry, "SUCCESS", "INFO", "请求正常完成，未观察到异常",
                        Map.of("outcome", "SUCCESS", "httpStatus", 200, "durationMs", 120),
                        3, 2));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.report()).doesNotContainKey("rootCause");
        assertThat(result.confidenceLevel()).isNull();
        assertThat(model.requests()).hasSize(2);
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
    void recordsLlmDisabledFailureWithoutAttemptingHttp() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentProperties disabled = new AgentProperties(
                false,
                new AgentProperties.DeepSeek("test-key", "http://127.0.0.1:1", "deepseek-flash",
                        0.1, 500, 1000, 3000),
                new AgentProperties.Limits(2, 2, 12000, 60000, 6000, 60));
        AgentModelClient model = new DeepSeekAgentModelClient(RestClient.builder(), disabled);
        DefaultAgentOrchestrator orchestrator = new DefaultAgentOrchestrator(
                model,
                new AgentPromptBuilder(AgentTestSupport.json()),
                new AgentDecisionParser(mapper()),
                new AgentToolCallValidator(),
                new AgentReportValidator(),
                invocationService(registry, AgentTestSupport.invocationRepository()),
                disabled);

        AgentRunResult result = orchestrator.diagnose(input(registry, 2, 2));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.FAILED);
        assertThat(result.failureReason()).contains("RCA_LLM_ENABLED=false");
        assertThat(result.report()).isNull();
        assertThat(result.decisionTrace()).anySatisfy(entry -> {
            assertThat(entry.get("status")).isEqualTo("FAILED");
            assertThat(entry.get("errorType")).isEqualTo("LLM_DISABLED");
            assertThat(entry.get("modelCallAttempted")).isEqualTo(false);
        });
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
    void stopsBeforeToolWhenBudgetIsExhaustedAfterModelDecision() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        ToolInvocationRepository repository = AgentTestSupport.invocationRepository();
        Duration budget = Duration.ofMillis(500);
        AtomicReference<Duration> modelRequestTimeout = new AtomicReference<>();
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
                modelRequestTimeout.set(request.timeout());
                // 明确消耗完本次模型调用获得的剩余预算，再额外等待 200ms，
                // 避免依赖 100ms 级别的调度边界判断。
                long sleepMs = request.timeout() != null
                        ? request.timeout().toMillis() + 200L : budget.toMillis() + 200L;
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return new AgentModelClient.ModelResponse(
                        callTool("query_logs", Map.of("service", "all", "limit", 20)),
                        modelName(), 12, 8, true);
            }
        };

        AgentRunResult result = orchestrator(slowModel, registry, repository, 3, 2)
                .diagnose(input(registry, 3, 2, budget));

        // 模型返回时整体预算已耗尽：按整体超时终止，不得再解析/执行工具。
        assertThat(modelRequestTimeout.get()).isNotNull();
        assertThat(modelRequestTimeout.get()).isLessThanOrEqualTo(budget);
        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.FAILED);
        assertThat(result.failureReason()).contains("超时");
        assertThat(result.decisionTrace()).anySatisfy(entry -> {
            assertThat(entry.get("type")).isEqualTo("MODEL_DECISION");
            assertThat(entry.get("decision")).isEqualTo("DEADLINE_EXCEEDED");
            assertThat(entry.get("status")).isEqualTo("REJECTED");
        });
        assertThat(result.decisionTrace())
                .noneMatch(entry -> "TOOL_EXECUTION".equals(entry.get("type")));
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
    void fallsBackToInsufficientEvidenceWhenModelCallLimitIsReached() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_logs", Map.of("service", "all", "limit", 20)),
                finalReport(List.of("log:order-service:log-1")));

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 1, 3)
                .diagnose(input(registry, 1, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.failureReason()).isNull();
        assertThat(result.report()).containsEntry("fallback", true)
                .containsEntry("fallbackReason", "MODEL_BUDGET_EXHAUSTED");
        assertThat(result.report().get("observedEvidenceIds")).asList()
                .contains("log:order-service:log-1");
        assertThat(result.decisionTrace()).anySatisfy(entry ->
                assertThat(entry.get("type")).isEqualTo("AGENT_FALLBACK"));
        assertThat(model.requests()).hasSize(1);
    }

    @Test
    void staysFailedWhenBudgetExhaustsWithoutAnyToolObservation() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_health", Map.of("service", "all")));

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 1, 1)
                .diagnose(input(registry, 1, 1));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.FAILED);
        assertThat(result.failureReason()).contains("最大模型调用轮数")
                .contains("TOOL_REJECTED");
        assertThat(result.report()).isNull();
        assertThat(result.decisionTrace()).noneMatch(entry ->
                "AGENT_FALLBACK".equals(entry.get("type")));
    }

    @Test
    void finalRoundReportWithoutAnyEvidenceFailsWithAuditableReason() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                finalReport(List.of("log:order-service:log-1")));

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 1, 2)
                .diagnose(input(registry, 1, 2));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.FAILED);
        assertThat(result.failureReason()).contains("最大模型调用轮数")
                .contains("尚无真实证据");
        assertThat(result.report()).isNull();
    }

    @Test
    void budgetFallbackDoesNotReportEarlierRejectionAsLastDecision() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_health", Map.of("service", "all")),
                callTool("query_logs", Map.of("service", "all", "limit", 20)));

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 2, 3)
                .diagnose(input(registry, 2, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.report()).containsEntry("fallback", true);
        // 第一轮工具被拒绝，但最后一轮成功取证；兜底不得把第一轮的拒绝写成最后决策。
        assertThat(result.report().get("lastDecision")).isNull();
        assertThat(String.valueOf(result.report().get("reason")))
                .doesNotContain("TOOL_REJECTED");
        assertThat(result.report().get("observedEvidenceIds")).asList()
                .contains("log:order-service:log-1");
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
    void reportsSlowResponseWhenLatencyIsLocalized() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())),
                tool("query_metrics", ToolResult.success(metricsOutput())));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_logs", Map.of("service", "all", "limit", 20)),
                finalReport(List.of("log:inventory-service:log-2"), "MEDIUM"));

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 4, 3)
                .diagnose(input(registry, "SLOW_RESPONSE", "WARNING",
                        "订单结算请求成功，但耗时 3529 ms，超过慢响应阈值 3000 ms",
                        Map.of("outcome", "SLOW_RESPONSE", "httpStatus", 200,
                                "durationMs", 3529, "orderStatus", "CONFIRMED"),
                        4, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.ROOT_CAUSE_IDENTIFIED);
        assertThat(result.confidenceLevel()).isEqualTo("MEDIUM");
        assertThat(result.report().get("supportingEvidenceIds")).asList()
                .containsExactly("log:inventory-service:log-2");
        assertThat(result.report().get("limitations")).asList().isNotEmpty();
        assertThat(result.decisionTrace()).noneMatch(entry ->
                "AGENT_FALLBACK".equals(entry.get("type")));
        assertThat(model.requests()).hasSize(2);
        assertThat(model.requests().get(0).systemPrompt())
                .contains("调用环节")
                .contains("内部原因")
                .doesNotContain("experimentFaultType");
    }

    @Test
    void reportsHttp500WithDownstreamEvidence() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_logs", Map.of("service", "all", "limit", 20)),
                finalReport(List.of("log:order-service:log-1"), "MEDIUM"));

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 4, 3)
                .diagnose(input(registry, "DOWNSTREAM_HTTP_5XX", "ERROR",
                        "订单结算失败，下游库存服务返回 5xx",
                        Map.of("outcome", "DOWNSTREAM_HTTP_5XX", "httpStatus", 502,
                                "durationMs", 122, "downstreamErrorType", "HTTP_5XX"),
                        4, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.ROOT_CAUSE_IDENTIFIED);
        assertThat(result.confidenceLevel()).isEqualTo("MEDIUM");
        assertThat(result.report().get("supportingEvidenceIds")).asList()
                .containsExactly("log:order-service:log-1");
    }

    @Test
    void promptsForTerminalActionOnFinalRound() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_logs", Map.of("service", "all", "limit", 20)),
                insufficientEvidence());

        orchestrator(model, registry, AgentTestSupport.invocationRepository(), 2, 3)
                .diagnose(input(registry, 2, 3));

        assertThat(model.requests()).hasSize(2);
        String finalPrompt = model.requests().get(1).userPrompt();
        assertThat(finalPrompt).contains("\"finalRound\":true")
                .contains("\"remainingModelCalls\":0")
                .contains("FINAL_REPORT_OR_INSUFFICIENT_EVIDENCE");
    }

    @Test
    void lastRoundDuplicateFallsBackToInsufficientEvidence() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_logs", Map.of("service", "all", "limit", 20)),
                callTool("query_logs", Map.of("service", "all", "limit", 20)));

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 2, 4)
                .diagnose(input(registry, 2, 4));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.report()).containsEntry("fallback", true)
                .containsEntry("fallbackReason", "MODEL_BUDGET_EXHAUSTED");
        assertThat(String.valueOf(result.report().get("lastDecision")))
                .contains("DUPLICATE_TOOL_CALL")
                .contains("query_logs|all|30|20|");
        assertThat(result.report().get("observedEvidenceIds")).asList()
                .contains("log:order-service:log-1");
        assertThat(result.decisionTrace()).anySatisfy(entry -> {
            assertThat(entry.get("type")).isEqualTo("TOOL_REJECTED");
            assertThat(String.valueOf(entry.get("error"))).contains("重复");
        });
        assertThat(result.decisionTrace()).anySatisfy(entry ->
                assertThat(entry.get("type")).isEqualTo("AGENT_FALLBACK"));
        assertThat(model.requests()).hasSize(2);
    }

    @Test
    void lastRoundRejectedReportFallsBackToInsufficientEvidence() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_logs", Map.of("service", "all", "limit", 20)),
                finalReport(List.of("log:order-service:not-exist")));

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 2, 3)
                .diagnose(input(registry, 2, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.report()).containsEntry("fallback", true);
        assertThat(String.valueOf(result.report().get("lastDecision")))
                .contains("FINAL_REPORT_REJECTED");
        assertThat(result.decisionTrace()).anySatisfy(entry ->
                assertThat(entry.get("type")).isEqualTo("AGENT_FALLBACK"));
    }

    @Test
    void warnsWhenToolReturnsNoNewEvidence() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())),
                tool("query_metrics", ToolResult.success(metricsOutput())));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_logs", Map.of("service", "all", "limit", 20)),
                callTool("query_metrics", Map.of("service", "all", "lookbackMinutes", 10)),
                callTool("query_logs", Map.of("service", "all", "limit", 30)),
                insufficientEvidence());

        AgentRunResult result = orchestrator(model, registry, AgentTestSupport.invocationRepository(), 4, 3)
                .diagnose(input(registry, 4, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.INSUFFICIENT_EVIDENCE);
        assertThat(model.requests()).hasSize(4);
        assertThat(model.requests().get(3).userPrompt())
                .contains("没有产生新的 evidenceId");
    }

    @Test
    void modelInputDoesNotContainExperimentPresetFields() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentInputFactory factory = new AgentInputFactory(
                AgentTestSupport.json(), registry,
                AgentTestSupport.externalServiceRegistry(), properties(3, 2));
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

    @Test
    void retriesLengthTruncationOnlyOnceThenFailsExplicitly() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentModelClient.ModelResponse truncated = new AgentModelClient.ModelResponse(
                "{\"action\":", "mock-deepseek", 100, 1200, true, "length");
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true, truncated, truncated);

        AgentRunResult result = orchestrator(model, registry,
                AgentTestSupport.invocationRepository(), 6, 3)
                .diagnose(input(registry, 6, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.FAILED);
        assertThat(result.failureReason()).contains("长度截断");
        assertThat(model.requests()).hasSize(2);
        assertThat(result.decisionTrace()).anySatisfy(entry -> {
            assertThat(entry.get("finishReason")).isEqualTo("length");
            assertThat(entry.get("lengthTruncated")).isEqualTo(true);
        });
    }

    @Test
    void oneLengthTruncationRetryCanRecover() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentModelClient.ModelResponse truncated = new AgentModelClient.ModelResponse(
                "{\"action\":", "mock-deepseek", 100, 1200, true, "length");
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true, truncated, insufficientEvidence());

        AgentRunResult result = orchestrator(model, registry,
                AgentTestSupport.invocationRepository(), 4, 3)
                .diagnose(input(registry, 4, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.INSUFFICIENT_EVIDENCE);
        assertThat(model.requests()).hasSize(2);
        assertThat(result.decisionTrace()).anySatisfy(entry ->
                assertThat(entry.get("decision")).isEqualTo("INVALID_OUTPUT"));
    }

    @Test
    void malformedJsonIsRejectedAndFedBackWithoutPretendingToBeEvidence() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true, "{not-json", insufficientEvidence());

        AgentRunResult result = orchestrator(model, registry,
                AgentTestSupport.invocationRepository(), 4, 3)
                .diagnose(input(registry, 4, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.report()).doesNotContainKey("rootCause");
        assertThat(model.requests()).hasSize(2);
        assertThat(result.decisionTrace()).anySatisfy(entry -> {
            assertThat(entry.get("decision")).isEqualTo("INVALID_OUTPUT");
            assertThat(entry.get("validationError")).isNotNull();
        });
    }

    @Test
    void lowConfidenceWithoutComponentEvidenceIsOnlyAHypothesis() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_health", ToolResult.success(healthOutput(false))));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_health", Map.of("service", "petclinic")),
                finalReport(List.of("health:petclinic:1"), "LOW", "petclinic"));

        AgentRunResult result = orchestrator(model, registry,
                AgentTestSupport.invocationRepository(), 4, 3)
                .diagnose(AgentTestSupport.externalInput(registry, "petclinic", 4, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.confidenceLevel()).isEqualTo("LOW");
        assertThat(result.report()).containsEntry("assessment", "LOW_CONFIDENCE_HYPOTHESIS")
                .containsEntry("componentEvidenceAvailable", false)
                .containsKey("rootCause");
    }

    @Test
    void lowConfidenceWithComponentEvidenceKeepsRootCauseStatus() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_health", ToolResult.success(healthOutput(true))));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_health", Map.of("service", "petclinic")),
                finalReport(List.of("health:petclinic:1:db"), "LOW", "petclinic"));

        AgentRunResult result = orchestrator(model, registry,
                AgentTestSupport.invocationRepository(), 4, 3)
                .diagnose(AgentTestSupport.externalInput(registry, "petclinic", 4, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.ROOT_CAUSE_IDENTIFIED);
        assertThat(result.report()).containsEntry("assessment", "ROOT_CAUSE_IDENTIFIED")
                .containsEntry("componentEvidenceAvailable", true);
    }

    @Test
    void failedProbeConnectivityEvidenceIsPreservedInInsufficientReport() {
        Map<String, Object> connectivityOutput = Map.of(
                "tool", "query_health",
                "available", false,
                "failureType", "CONNECTIVITY_FAILURE",
                "evidenceIds", List.of("connectivity:petclinic:health:1"));
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_health", ToolResult.failure(
                        "query_health 不可用: 连接被拒绝或服务未启动", connectivityOutput)));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_health", Map.of("service", "petclinic")),
                insufficientEvidence());

        AgentRunResult result = orchestrator(model, registry,
                AgentTestSupport.invocationRepository(), 4, 3)
                .diagnose(AgentTestSupport.externalInput(registry, "petclinic", 4, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.report().get("observedEvidenceIds")).asList()
                .containsExactly("connectivity:petclinic:health:1");
        assertThat(result.decisionTrace()).anySatisfy(entry -> {
            if ("TOOL_EXECUTION".equals(entry.get("type"))) {
                assertThat(entry.get("status")).isEqualTo("FAILED");
                assertThat(entry.get("evidenceIds")).asList()
                        .containsExactly("connectivity:petclinic:health:1");
            }
        });
    }

    @Test
    void connectivityOnlyFinalReportIsNotConfirmedAsRootCause() {
        Map<String, Object> connectivityOutput = Map.of(
                "tool", "query_health",
                "available", false,
                "failureType", "CONNECTIVITY_FAILURE",
                "evidenceIds", List.of("connectivity:petclinic:health:1"));
        DiagnosticToolRegistry registry = AgentTestSupport.registry(
                tool("query_health", ToolResult.failure(
                        "query_health 不可用: 连接被拒绝或服务未启动", connectivityOutput)));
        AgentTestSupport.ScriptedModelClient model = new AgentTestSupport.ScriptedModelClient(
                true,
                callTool("query_health", Map.of("service", "petclinic")),
                finalReport(List.of("connectivity:petclinic:health:1"), "MEDIUM", "petclinic"));

        AgentRunResult result = orchestrator(model, registry,
                AgentTestSupport.invocationRepository(), 4, 3)
                .diagnose(AgentTestSupport.externalInput(registry, "petclinic", 4, 3));

        assertThat(result.status()).isEqualTo(AgentOrchestrator.RunStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.confidenceLevel()).isEqualTo("LOW");
        assertThat(result.report())
                .containsEntry("assessment", "LOW_CONFIDENCE_HYPOTHESIS")
                .containsEntry("componentEvidenceAvailable", false);
        assertThat(result.decisionTrace()).anySatisfy(entry -> {
            if ("FINAL_REPORT_ACCEPTED".equals(entry.get("decision"))) {
                assertThat(entry.get("connectivityEvidenceOnly")).isEqualTo(true);
            }
        });
    }

    private Map<String, Object> healthOutput(boolean withComponents) {
        if (withComponents) {
            return Map.of(
                    "tool", "query_health",
                    "status", "DOWN",
                    "evidenceIds", List.of("health:petclinic:1", "health:petclinic:1:db"),
                    "componentEvidenceIds", List.of("health:petclinic:1:db"),
                    "components", List.of(Map.of(
                            "path", "db",
                            "status", "DOWN",
                            "evidenceId", "health:petclinic:1:db")));
        }
        return Map.of(
                "tool", "query_health",
                "status", "DOWN",
                "evidenceIds", List.of("health:petclinic:1"));
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
