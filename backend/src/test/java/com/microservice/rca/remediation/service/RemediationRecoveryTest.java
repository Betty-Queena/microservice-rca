package com.microservice.rca.remediation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.rca.common.JsonSupport;
import com.microservice.rca.remediation.domain.RecoveryCodes;
import com.microservice.rca.remediation.domain.RemediationAction;
import com.microservice.rca.remediation.domain.RemediationActionType;
import com.microservice.rca.remediation.domain.RemediationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RemediationRecoveryTest {

    private static final Instant CUTOFF = Instant.parse("2026-10-24T10:00:00Z");
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID REMEDIATION_ID = UUID.randomUUID();

    private final RemediationStore store = mock(RemediationStore.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final JsonSupport jsonSupport = new JsonSupport(objectMapper);
    private final RemediationRecovery recovery = new RemediationRecovery(
            store, jsonSupport, objectMapper, CUTOFF);

    @Test
    void pendingBeforeActionIsMarkedWithoutSideEffects() {
        RemediationAction pending = action(RemediationStatus.PENDING);
        stubCandidate(pending);

        recovery.recover(CUTOFF);

        CapturedMark mark = captureMark(RemediationStatus.PENDING);
        assertThat(mark.code()).isEqualTo(RecoveryCodes.BEFORE_ACTION);
        assertThat(mark.reason()).contains("动作确定尚未开始");
        JsonNode last = lastAudit(mark.audit());
        assertThat(last.path("step").asText()).isEqualTo("RECOVERY_MARKED");
        assertThat(last.path("previousStatus").asText()).isEqualTo("PENDING");
        assertThat(last.path("actionState").asText()).isEqualTo("NOT_STARTED");
        assertThat(last.path("verificationState").asText()).isEqualTo("NOT_STARTED");
        assertThat(last.path("manualReviewRequired").asBoolean()).isTrue();
        assertThat(last.path("processStartupCutoff").asText()).isEqualTo(CUTOFF.toString());
        assertThat(last.path("actionResultRecorded").asBoolean()).isFalse();
        assertThat(last.path("verificationResultRecorded").asBoolean()).isFalse();
    }

    @Test
    void executingWithoutActionResultIsUnknown() {
        RemediationAction executing = action(RemediationStatus.EXECUTING);
        stubCandidate(executing);

        recovery.recover(CUTOFF);

        CapturedMark mark = captureMark(RemediationStatus.EXECUTING);
        assertThat(mark.code()).isEqualTo(RecoveryCodes.ACTION_UNKNOWN);
        assertThat(mark.reason()).contains("无法确认条件关闭接口是否已被调用");
        JsonNode last = lastAudit(mark.audit());
        assertThat(last.path("actionState").asText()).isEqualTo("UNKNOWN");
        assertThat(last.path("verificationState").asText()).isEqualTo("NOT_STARTED");
    }

    @Test
    void unreadableActionResultIsNotReportedAsFailure() {
        RemediationAction executing = action(RemediationStatus.EXECUTING);
        executing.setActionResult("not-json");
        stubCandidate(executing);

        recovery.recover(CUTOFF);

        CapturedMark mark = captureMark(RemediationStatus.EXECUTING);
        assertThat(mark.code()).isEqualTo(RecoveryCodes.ACTION_RESULT_UNREADABLE);
        assertThat(mark.reason()).contains("无法读取 success 布尔值");
        assertThat(lastAudit(mark.audit()).path("actionState").asText())
                .isEqualTo("UNKNOWN");
    }

    @Test
    void explicitlyFailedActionKeepsFailedSemantics() {
        RemediationAction executing = action(RemediationStatus.EXECUTING);
        executing.setActionResult(
                "{\"success\":false,\"outcome\":\"FAULT_DISABLE_CONFLICT\"}");
        stubCandidate(executing);

        recovery.recover(CUTOFF);

        CapturedMark mark = captureMark(RemediationStatus.EXECUTING);
        assertThat(mark.code()).isEqualTo(RecoveryCodes.ACTION_FAILED);
        assertThat(mark.reason())
                .contains("action_result 已记录动作失败")
                .contains("FAULT_DISABLE_CONFLICT");
        JsonNode last = lastAudit(mark.audit());
        assertThat(last.path("actionState").asText()).isEqualTo("FAILED");
        assertThat(last.path("verificationState").asText()).isEqualTo("NOT_STARTED");
    }

    @Test
    void successfulActionWithUnpersistedVerificationIsUnknownAndPreservesResult() {
        RemediationAction executing = action(RemediationStatus.EXECUTING);
        String actionResult = "{\"success\":true,\"outcome\":\"DISABLED\"}";
        executing.setActionResult(actionResult);
        stubCandidate(executing);

        recovery.recover(CUTOFF);

        CapturedMark mark = captureMark(RemediationStatus.EXECUTING);
        assertThat(mark.code()).isEqualTo(RecoveryCodes.VERIFICATION_UNKNOWN);
        assertThat(mark.reason())
                .contains("action_result 已记录动作成功")
                .contains("验证请求可能已执行");
        JsonNode last = lastAudit(mark.audit());
        assertThat(last.path("actionState").asText()).isEqualTo("SUCCEEDED");
        assertThat(last.path("verificationState").asText()).isEqualTo("UNKNOWN");
        // 恢复不修改原始动作结果。
        assertThat(executing.getActionResult()).isEqualTo(actionResult);
        assertThat(executing.getVerificationResult()).isNull();
    }

    @Test
    void inconsistentActiveRowIsMarkedForManualInvestigation() {
        RemediationAction executing = action(RemediationStatus.EXECUTING);
        executing.setActionResult("{\"success\":true}");
        executing.setVerificationResult("{\"result\":\"PASS\"}");
        stubCandidate(executing);

        recovery.recover(CUTOFF);

        CapturedMark mark = captureMark(RemediationStatus.EXECUTING);
        assertThat(mark.code()).isEqualTo(RecoveryCodes.STATE_INCONSISTENT);
        assertThat(mark.reason()).contains("字段与状态不一致");
        JsonNode last = lastAudit(mark.audit());
        assertThat(last.path("actionState").asText()).isEqualTo("UNKNOWN");
        assertThat(last.path("verificationState").asText()).isEqualTo("UNKNOWN");
        assertThat(last.path("consistencyIssues").isArray()).isTrue();
        assertThat(last.path("consistencyIssues").toString())
                .contains("动作结果未确认但已有 verification_result");
        // 原始验证结果保留，恢复不覆盖。
        assertThat(executing.getVerificationResult()).isEqualTo("{\"result\":\"PASS\"}");
    }

    @Test
    void existingAuditEntriesArePreservedAndRecoveryEntryAppended() {
        RemediationAction pending = action(RemediationStatus.PENDING);
        pending.setAuditTrace("[{\"step\":\"REQUEST_ACCEPTED\",\"at\":\"2026-10-24T09:59:00Z\"}]");
        stubCandidate(pending);

        recovery.recover(CUTOFF);

        JsonNode audit = captureMark(RemediationStatus.PENDING).audit();
        assertThat(audit.size()).isEqualTo(2);
        assertThat(audit.get(0).path("step").asText()).isEqualTo("REQUEST_ACCEPTED");
        assertThat(audit.get(1).path("step").asText()).isEqualTo("RECOVERY_MARKED");
    }

    @Test
    void unparseableAuditIsWrappedInsteadOfDiscarded() {
        RemediationAction pending = action(RemediationStatus.PENDING);
        pending.setAuditTrace("not-json");
        stubCandidate(pending);

        recovery.recover(CUTOFF);

        JsonNode audit = captureMark(RemediationStatus.PENDING).audit();
        assertThat(audit.size()).isEqualTo(2);
        assertThat(audit.get(0).path("previousAuditTrace").path("unparseable").asText())
                .isEqualTo("not-json");
        assertThat(audit.get(1).path("step").asText()).isEqualTo("RECOVERY_MARKED");
    }

    @Test
    void terminalRowsAreSkippedDefensively() {
        RemediationAction succeeded = action(RemediationStatus.SUCCEEDED);
        stubCandidate(succeeded);

        recovery.recover(CUTOFF);

        verify(store, never()).markInterrupted(
                any(), any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void concurrentCasFailureDoesNotThrow() {
        RemediationAction pending = action(RemediationStatus.PENDING);
        stubCandidate(pending);
        when(store.markInterrupted(any(), any(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        assertThatCode(() -> recovery.recover(CUTOFF)).doesNotThrowAnyException();
        verify(store).markInterrupted(any(), any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void oneRowFailureDoesNotPreventOtherRows() {
        RemediationAction first = action(RemediationStatus.PENDING);
        first.setId(UUID.randomUUID());
        RemediationAction second = action(RemediationStatus.PENDING);
        second.setId(UUID.randomUUID());
        when(store.findInterruptedBefore(CUTOFF)).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("boom")).doReturn(true)
                .when(store).markInterrupted(
                        any(), any(), anyLong(), any(), any(), any(), any(), any());

        assertThatCode(() -> recovery.recover(CUTOFF)).doesNotThrowAnyException();
        verify(store, times(2)).markInterrupted(
                any(), any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void queryFailureDoesNotPreventApplicationStartup() {
        when(store.findInterruptedBefore(CUTOFF))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(() -> recovery.recover(CUTOFF)).doesNotThrowAnyException();
        verify(store, never()).markInterrupted(
                any(), any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void secondRunDoesNotModifyAlreadyRecoveredRows() {
        RemediationAction pending = action(RemediationStatus.PENDING);
        when(store.findInterruptedBefore(CUTOFF)).thenReturn(List.of(pending), List.of());
        when(store.markInterrupted(any(), any(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        recovery.recover(CUTOFF);
        recovery.recover(CUTOFF);

        verify(store, times(1)).markInterrupted(
                any(), any(), anyLong(), any(), any(), any(), any(), any());
    }

    private void stubCandidate(RemediationAction action) {
        when(store.findInterruptedBefore(CUTOFF)).thenReturn(List.of(action));
        when(store.markInterrupted(any(), any(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(true);
    }

    private CapturedMark captureMark(RemediationStatus expectedStatus) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> audit = ArgumentCaptor.forClass(String.class);
        verify(store).markInterrupted(
                eq(REMEDIATION_ID),
                eq(expectedStatus),
                eq(0L),
                eq(RemediationStatus.RECOVERY_REQUIRED),
                code.capture(),
                reason.capture(),
                audit.capture(),
                any(Instant.class));
        try {
            return new CapturedMark(code.getValue(), reason.getValue(),
                    objectMapper.readTree(audit.getValue()));
        } catch (Exception ex) {
            throw new AssertionError("无法解析恢复审计 JSON", ex);
        }
    }

    private JsonNode lastAudit(JsonNode audit) {
        assertThat(audit.isArray()).isTrue();
        assertThat(audit.size()).isGreaterThan(0);
        return audit.get(audit.size() - 1);
    }

    private RemediationAction action(RemediationStatus status) {
        RemediationAction action = new RemediationAction();
        action.setId(REMEDIATION_ID);
        action.setFaultEventId(EVENT_ID);
        action.setDiagnosisTaskId(TASK_ID);
        action.setActionType(RemediationActionType.DISABLE_EXPERIMENT_FAULT);
        action.setTargetService("inventory-service");
        action.setExperimentId(UUID.randomUUID());
        action.setStatus(status);
        action.setRequestedBy("recovery-test");
        action.setRequestedAt(CUTOFF.minusSeconds(60));
        action.setEventStatusUpdated(false);
        if (status == RemediationStatus.EXECUTING) {
            action.setStartedAt(CUTOFF.minusSeconds(30));
        }
        return action;
    }

    private record CapturedMark(String code, String reason, JsonNode audit) {
    }
}
