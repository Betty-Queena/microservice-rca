package com.microservice.rca.agent;

import static com.microservice.rca.agent.AgentTestSupport.json;
import static com.microservice.rca.agent.AgentTestSupport.properties;
import static com.microservice.rca.agent.AgentTestSupport.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microservice.rca.diagnosis.domain.DiagnosisResultStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.domain.FaultEventStatus;
import com.microservice.rca.diagnosis.domain.FaultSeverity;
import com.microservice.rca.diagnosis.repository.DiagnosisTaskRepository;
import com.microservice.rca.diagnosis.repository.FaultEventRepository;
import com.microservice.rca.tools.DiagnosticToolRegistry;
import com.microservice.rca.tools.ToolResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class AgentTaskRunnerTest {

    private final DiagnosisTaskRepository taskRepository = mock(DiagnosisTaskRepository.class);
    private final FaultEventRepository eventRepository = mock(FaultEventRepository.class);
    private final AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
    private final AgentModelClient modelClient = mock(AgentModelClient.class);
    private final DiagnosticToolRegistry registry = AgentTestSupport.registry(
            tool("query_logs", ToolResult.success(AgentTestSupport.logsOutput())));

    private DiagnosisTask task;
    private FaultEvent event;
    private AgentTaskRunner runner;

    @BeforeEach
    void setUp() {
        task = new DiagnosisTask();
        task.setId(UUID.randomUUID());
        task.setFaultEventId(UUID.randomUUID());
        task.setStatus(DiagnosisStatus.PENDING);
        task.setTriggerSource("AGENT");
        task.setGoal("定位根因");

        event = new FaultEvent();
        event.setId(task.getFaultEventId());
        event.setServiceName("inventory-service");
        event.setEventType("DOWNSTREAM_TIMEOUT");
        event.setSeverity(FaultSeverity.ERROR);
        event.setSummary("订单结算调用库存服务超时");
        event.setOccurredAt(Instant.now().minusSeconds(30));
        event.setRequestId("exp-abc123");

        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(taskRepository.save(any(DiagnosisTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.findById(task.getFaultEventId())).thenReturn(Optional.of(event));
        when(modelClient.modelName()).thenReturn("deepseek-flash");

        runner = new AgentTaskRunner(
                taskRepository,
                eventRepository,
                new AgentInputFactory(json(), registry,
                        AgentTestSupport.externalServiceRegistry(), properties(4, 3)),
                orchestrator,
                modelClient,
                json());
    }

    @Test
    void persistsSuccessfulResultAndDecisionTrace() {
        when(orchestrator.diagnose(any())).thenReturn(AgentOrchestrator.AgentRunResult.rootCause(
                Map.of("rootCause", "库存服务响应延迟导致订单结算超时"),
                List.of(Map.of("type", "TOOL_EXECUTION", "tool", "query_logs")),
                "deepseek-flash",
                "HIGH"));

        runner.run(task.getId());

        DiagnosisTask saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(DiagnosisStatus.SUCCEEDED);
        assertThat(saved.getResultStatus()).isEqualTo(DiagnosisResultStatus.ROOT_CAUSE_IDENTIFIED);
        assertThat(saved.getConfidenceLevel()).isEqualTo("HIGH");
        assertThat(saved.getRootCause()).contains("库存服务");
        assertThat(saved.getReport()).contains("rootCause");
        assertThat(saved.getDecisionTrace()).contains("TOOL_EXECUTION");
        assertThat(saved.getAgentInput()).contains("windowStart");
        assertThat(saved.getModelName()).isEqualTo("deepseek-flash");
        assertThat(saved.getStartedAt()).isNotNull();
        assertThat(saved.getCompletedAt()).isNotNull();
        assertThat(saved.getFailureReason()).isNull();
    }

    @Test
    void persistsInsufficientEvidenceWithoutRootCause() {
        when(orchestrator.diagnose(any())).thenReturn(AgentOrchestrator.AgentRunResult
                .insufficientEvidence(
                        Map.of("reason", "只有一条日志，无法区分延迟来源"),
                        List.of(Map.of("type", "INSUFFICIENT_EVIDENCE")),
                        "deepseek-flash"));

        runner.run(task.getId());

        DiagnosisTask saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(DiagnosisStatus.SUCCEEDED);
        assertThat(saved.getResultStatus()).isEqualTo(DiagnosisResultStatus.INSUFFICIENT_EVIDENCE);
        assertThat(saved.getRootCause()).isNull();
        assertThat(saved.getConfidenceLevel()).isNull();
        assertThat(saved.getConclusion()).contains("证据不足");
        assertThat(saved.getFailureReason()).isNull();
    }

    @Test
    void persistsBudgetFallbackAsInconclusiveWithoutWritingRootCause() {
        when(orchestrator.diagnose(any())).thenReturn(AgentOrchestrator.AgentRunResult
                .insufficientEvidence(
                        Map.of(
                                "reason", "模型调用次数达到上限（6），最后一轮未形成最终报告",
                                "fallback", true,
                                "observedEvidenceIds", List.of("log:order-service:log-1")),
                        List.of(Map.of("type", "AGENT_FALLBACK")),
                        "deepseek-flash"));

        runner.run(task.getId());

        DiagnosisTask saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(DiagnosisStatus.SUCCEEDED);
        assertThat(saved.getResultStatus()).isEqualTo(DiagnosisResultStatus.INSUFFICIENT_EVIDENCE);
        assertThat(saved.getRootCause()).isNull();
        assertThat(saved.getConfidenceLevel()).isNull();
        assertThat(saved.getConclusion()).startsWith("未形成结论")
                .doesNotStartWith("证据不足");
        assertThat(saved.getReport()).contains("\"fallback\":true");
        assertThat(saved.getFailureReason()).isNull();
    }

    @Test
    void persistsLowConfidenceHypothesisWithoutWritingRootCause() {
        when(orchestrator.diagnose(any())).thenReturn(
                AgentOrchestrator.AgentRunResult.lowConfidenceHypothesis(
                        Map.of(
                                "result", "INSUFFICIENT_EVIDENCE",
                                "assessment", "LOW_CONFIDENCE_HYPOTHESIS",
                                "rootCause", "可能是数据库连接失败",
                                "hypothesis", "数据库连接池无法获取连接",
                                "componentEvidenceAvailable", false),
                        List.of(Map.of("type", "MODEL_DECISION")),
                        "deepseek-flash"));

        runner.run(task.getId());

        DiagnosisTask saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(DiagnosisStatus.SUCCEEDED);
        assertThat(saved.getResultStatus()).isEqualTo(DiagnosisResultStatus.INSUFFICIENT_EVIDENCE);
        assertThat(saved.getConfidenceLevel()).isEqualTo("LOW");
        assertThat(saved.getRootCause()).isNull();
        assertThat(saved.getConclusion()).contains("低置信度假说").contains("数据库连接池");
        assertThat(saved.getReport()).contains("LOW_CONFIDENCE_HYPOTHESIS");
        assertThat(saved.getFailureReason()).isNull();
    }

    @Test
    void persistsModelFailureAsFailedAndRetryable() {
        when(orchestrator.diagnose(any())).thenReturn(AgentOrchestrator.AgentRunResult.failed(
                "DeepSeek 调用超时（60000 ms）",
                List.of(Map.of("type", "MODEL_DECISION", "status", "FAILED",
                        "modelCallAttempted", true)),
                "deepseek-flash"));

        runner.run(task.getId());

        DiagnosisTask saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(DiagnosisStatus.FAILED);
        assertThat(saved.getResultStatus()).isNull();
        assertThat(saved.getRootCause()).isNull();
        assertThat(saved.getConfidenceLevel()).isNull();
        assertThat(saved.getFailureReason()).contains("超时");
        assertThat(saved.getCompletedAt()).isNotNull();
    }

    @Test
    void restoresEventToOpenAfterSuccessfulDiagnosisAndSummarizesUsage() {
        event.setStatus(FaultEventStatus.DIAGNOSING);
        when(orchestrator.diagnose(any())).thenReturn(AgentOrchestrator.AgentRunResult.rootCause(
                Map.of("rootCause", "库存服务响应延迟导致订单结算超时"),
                List.of(
                        Map.of("type", "MODEL_DECISION", "decision", "CALL_TOOL",
                                "usageAvailable", true, "promptTokens", 12,
                                "completionTokens", 8, "latencyMs", 100),
                        Map.of("type", "MODEL_DECISION", "decision", "FINAL_REPORT_ACCEPTED",
                                "usageAvailable", true, "promptTokens", 30,
                                "completionTokens", 20, "latencyMs", 150)),
                "deepseek-flash",
                "HIGH"));

        runner.run(task.getId());

        DiagnosisTask saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(DiagnosisStatus.SUCCEEDED);
        // 诊断完成不等于故障解决，事件通过条件更新回到待人工处置。
        verify(eventRepository).updateStatusIfCurrent(
                eq(event.getId()), eq(FaultEventStatus.DIAGNOSING),
                eq(FaultEventStatus.OPEN), any(Instant.class));
        assertThat(saved.getReport()).contains("\"modelCallCount\":2");
        assertThat(saved.getReport()).contains("\"promptTokens\":42");
        assertThat(saved.getReport()).contains("\"completionTokens\":28");
        assertThat(saved.getReport()).contains("\"totalTokens\":70");
        assertThat(saved.getReport()).contains("\"modelCallsWithoutUsage\":0");
        assertThat(saved.getReport()).contains("\"modelLatencyMsTotal\":250");
        assertThat(saved.getDecisionTrace()).contains("AGENT_RUN_SUMMARY");
    }

    @Test
    void marksMissingUsageExplicitlyWithoutFabricatingTokens() {
        when(orchestrator.diagnose(any())).thenReturn(AgentOrchestrator.AgentRunResult.rootCause(
                Map.of("rootCause", "库存服务响应延迟导致订单结算超时"),
                List.of(Map.of("type", "MODEL_DECISION", "decision", "FINAL_REPORT_ACCEPTED",
                        "usageAvailable", false, "latencyMs", 80)),
                "deepseek-flash",
                "MEDIUM"));

        runner.run(task.getId());

        DiagnosisTask saved = lastSaved();
        assertThat(saved.getReport()).contains("\"modelCallCount\":1");
        assertThat(saved.getReport()).contains("\"modelCallsWithoutUsage\":1");
        assertThat(saved.getReport()).contains("\"totalTokens\":0");
        assertThat(saved.getReport()).contains("未返回 usage");
        assertThat(saved.getDecisionTrace()).contains("AGENT_RUN_SUMMARY");
    }

    @Test
    void doesNotCountConfigurationFailureAsModelCall() {
        when(orchestrator.diagnose(any())).thenReturn(AgentOrchestrator.AgentRunResult.failed(
                "未配置 DEEPSEEK_API_KEY，Agent 未调用模型",
                List.of(Map.of("type", "MODEL_DECISION", "status", "FAILED",
                        "decision", "MODEL_ERROR", "modelCallAttempted", false)),
                "deepseek-flash"));

        runner.run(task.getId());

        DiagnosisTask saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(DiagnosisStatus.FAILED);
        assertThat(saved.getDecisionTrace()).contains("\"modelCallCount\":0");
        assertThat(saved.getDecisionTrace()).contains("本次运行未调用模型");
    }

    @Test
    void reopensEventWhenDiagnosisFails() {
        event.setStatus(FaultEventStatus.DIAGNOSING);
        when(orchestrator.diagnose(any())).thenReturn(AgentOrchestrator.AgentRunResult.failed(
                "未配置 DEEPSEEK_API_KEY，Agent 未调用模型，也未生成任何诊断结论",
                List.of(Map.of("type", "MODEL_DECISION", "status", "FAILED")),
                "deepseek-flash"));

        runner.run(task.getId());

        verify(eventRepository).updateStatusIfCurrent(
                eq(event.getId()), eq(FaultEventStatus.DIAGNOSING),
                eq(FaultEventStatus.OPEN), any(Instant.class));
    }

    @Test
    void recordsUnexpectedExceptionAndDoesNotFabricateDiagnosis() {
        when(orchestrator.diagnose(any())).thenThrow(new IllegalStateException("boom"));

        runner.run(task.getId());

        DiagnosisTask saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(DiagnosisStatus.FAILED);
        assertThat(saved.getFailureReason()).contains("Agent 执行异常");
        assertThat(saved.getDecisionTrace()).contains("AGENT_ERROR");
        assertThat(saved.getRootCause()).isNull();
        verify(orchestrator).diagnose(any());
    }

    @Test
    void persistsFailedWhenInitialRunningSaveFails() {
        when(taskRepository.save(any(DiagnosisTask.class)))
                .thenThrow(new DataIntegrityViolationException("database unavailable"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        runner.run(task.getId());

        DiagnosisTask saved = lastSaved();
        assertThat(saved.getStatus()).isEqualTo(DiagnosisStatus.FAILED);
        assertThat(saved.getFailureReason()).contains("Agent 执行异常");
        assertThat(saved.getCompletedAt()).isNotNull();
        verify(orchestrator, never()).diagnose(any());
    }

    @Test
    void doesNotOverwriteCancelledTaskWithDiagnosisResult() {
        when(orchestrator.diagnose(any())).thenAnswer(invocation -> {
            // 模拟执行期间人工将任务取消。
            task.setStatus(DiagnosisStatus.CANCELLED);
            return AgentOrchestrator.AgentRunResult.rootCause(
                    Map.of("rootCause", "不应写入的结论"),
                    List.of(Map.of("type", "MODEL_DECISION")),
                    "deepseek-flash",
                    "HIGH");
        });

        runner.run(task.getId());

        assertThat(task.getStatus()).isEqualTo(DiagnosisStatus.CANCELLED);
        assertThat(task.getRootCause()).isNull();
        assertThat(task.getReport()).isNull();
        verify(eventRepository, never()).updateStatusIfCurrent(
                any(), any(), any(), any());
    }

    @Test
    void doesNotOverwriteCancelledTaskWhenFailureOccurs() {
        when(orchestrator.diagnose(any())).thenAnswer(invocation -> {
            task.setStatus(DiagnosisStatus.CANCELLED);
            throw new IllegalStateException("boom");
        });

        runner.run(task.getId());

        assertThat(task.getStatus()).isEqualTo(DiagnosisStatus.CANCELLED);
        assertThat(task.getFailureReason()).isNull();
        verify(eventRepository, never()).updateStatusIfCurrent(
                any(), any(), any(), any());
    }

    @Test
    void eventRestoreFailureDoesNotOverwriteSuccessfulTask() {
        when(orchestrator.diagnose(any())).thenReturn(AgentOrchestrator.AgentRunResult.rootCause(
                Map.of("rootCause", "库存服务响应延迟导致订单结算超时"),
                List.of(Map.of("type", "MODEL_DECISION")),
                "deepseek-flash",
                "HIGH"));
        when(eventRepository.updateStatusIfCurrent(any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("event update failed"));

        runner.run(task.getId());

        assertThat(task.getStatus()).isEqualTo(DiagnosisStatus.SUCCEEDED);
        assertThat(task.getRootCause()).contains("库存服务");
        assertThat(task.getFailureReason()).isNull();
        assertThat(task.getCompletedAt()).isNotNull();
    }

    @Test
    void doesNotOverwriteSucceededTaskWhenFailurePathRunsAfterConcurrentCompletion() {
        when(orchestrator.diagnose(any())).thenAnswer(invocation -> {
            // 模拟另一执行流已完成任务后本线程才进入异常处理。
            task.setStatus(DiagnosisStatus.SUCCEEDED);
            throw new IllegalStateException("late failure");
        });

        runner.run(task.getId());

        assertThat(task.getStatus()).isEqualTo(DiagnosisStatus.SUCCEEDED);
        assertThat(task.getFailureReason()).isNull();
        verify(eventRepository, never()).updateStatusIfCurrent(
                any(), any(), any(), any());
    }

    private DiagnosisTask lastSaved() {
        ArgumentCaptor<DiagnosisTask> captor = ArgumentCaptor.forClass(DiagnosisTask.class);
        verify(taskRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
