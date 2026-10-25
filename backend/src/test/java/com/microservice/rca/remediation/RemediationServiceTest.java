package com.microservice.rca.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.rca.common.JsonSupport;
import com.microservice.rca.diagnosis.domain.DiagnosisResultStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.domain.FaultEventStatus;
import com.microservice.rca.diagnosis.repository.DiagnosisTaskRepository;
import com.microservice.rca.diagnosis.service.FaultEventService;
import com.microservice.rca.remediation.api.CreateRemediationRequest;
import com.microservice.rca.remediation.RemediationProperties;
import com.microservice.rca.remediation.api.RemediationView;
import com.microservice.rca.remediation.domain.RecoveryCodes;
import com.microservice.rca.remediation.domain.RemediationAction;
import com.microservice.rca.remediation.domain.RemediationActionType;
import com.microservice.rca.remediation.domain.RemediationStatus;
import com.microservice.rca.remediation.service.RemediationExecutor;
import com.microservice.rca.remediation.service.RemediationExecutor.ActionOutcome;
import com.microservice.rca.remediation.service.RemediationExecutor.VerificationOutcome;
import com.microservice.rca.remediation.service.RemediationService;
import com.microservice.rca.remediation.service.RemediationStore;
import com.microservice.rca.remediation.service.RemediationStore.FinishResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RemediationServiceTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID EXPERIMENT_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID REMEDIATION_ID = UUID.randomUUID();
    private static final UUID PRIOR_REMEDIATION_ID = UUID.randomUUID();

    private final RemediationStore store = mock(RemediationStore.class);
    private final FaultEventService eventService = mock(FaultEventService.class);
    private final DiagnosisTaskRepository taskRepository = mock(DiagnosisTaskRepository.class);
    private final RemediationExecutor executor = mock(RemediationExecutor.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final JsonSupport jsonSupport = new JsonSupport(objectMapper);
    private final AtomicReference<RemediationAction> persisted = new AtomicReference<>();
    private final RemediationService service = new RemediationService(
            new RemediationProperties(true), store, eventService, taskRepository,
            executor, jsonSupport);

    private FaultEvent event;
    private DiagnosisTask diagnosisTask;

    @BeforeEach
    void setUp() {
        event = event(FaultEventStatus.OPEN, "EXPERIMENT", EXPERIMENT_ID);
        diagnosisTask = task(TASK_ID, EVENT_ID, "AGENT",
                DiagnosisStatus.SUCCEEDED, DiagnosisResultStatus.ROOT_CAUSE_IDENTIFIED);

        when(eventService.requireEvent(EVENT_ID)).thenReturn(event);
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(diagnosisTask));
        when(taskRepository.existsByFaultEventIdAndTriggerSourceInAndStatusIn(
                eq(EVENT_ID), any(), any())).thenReturn(false);
        when(store.findByEventAndIdempotencyKey(eq(EVENT_ID), anyString()))
                .thenReturn(Optional.empty());
        when(store.findActiveByEvent(EVENT_ID)).thenReturn(Optional.empty());
        when(store.findLatestByEvent(EVENT_ID)).thenReturn(Optional.empty());
        when(store.create(any())).thenAnswer(invocation -> {
            RemediationAction action = invocation.getArgument(0);
            action.setId(REMEDIATION_ID);
            persisted.set(action);
            return action;
        });
        when(store.findById(any(UUID.class))).thenAnswer(invocation ->
                Optional.ofNullable(persisted.get()));
        when(store.claim(eq(REMEDIATION_ID), any())).thenAnswer(invocation -> {
            persisted.get().setStartedAt(invocation.getArgument(1));
            return true;
        });
        when(store.updateAudit(eq(REMEDIATION_ID), anyString())).thenAnswer(invocation -> {
            persisted.get().setAuditTrace(invocation.getArgument(1));
            return true;
        });
        when(store.recordAction(eq(REMEDIATION_ID), any(), any())).thenAnswer(invocation -> {
            persisted.get().setActionResult(invocation.<String>getArgument(1));
            persisted.get().setAuditTrace(invocation.<String>getArgument(2));
            return true;
        });
        when(store.finishSucceeded(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    RemediationAction action = persisted.get();
                    action.setStatus(RemediationStatus.SUCCEEDED);
                    action.setActionResult(invocation.<String>getArgument(2));
                    action.setVerificationResult(invocation.<String>getArgument(3));
                    action.setAuditTrace(invocation.<String>getArgument(4));
                    action.setVerificationRequestId(invocation.<String>getArgument(5));
                    action.setVerificationFaultEventId(invocation.getArgument(6));
                    action.setEventStatusUpdated(true);
                    action.setCompletedAt(invocation.getArgument(7));
                    return new FinishResult(true, true);
                });
        when(store.finishFailed(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    RemediationAction action = persisted.get();
                    action.setStatus(invocation.<RemediationStatus>getArgument(1));
                    action.setFailureCode(invocation.<String>getArgument(2));
                    action.setFailureReason(invocation.<String>getArgument(3));
                    action.setActionResult(invocation.<String>getArgument(4));
                    action.setVerificationResult(invocation.<String>getArgument(5));
                    action.setAuditTrace(invocation.<String>getArgument(6));
                    action.setVerificationRequestId(invocation.<String>getArgument(7));
                    action.setVerificationFaultEventId(invocation.getArgument(8));
                    action.setCompletedAt(invocation.getArgument(9));
                    return true;
                });
        when(executor.executeAction(event)).thenReturn(actionSuccess());
        when(executor.verify(event)).thenReturn(verificationPass());
    }

    @Test
    void disabledSwitchRejectsBeforeLoadingEvent() {
        RemediationService disabled = new RemediationService(new RemediationProperties(false),
                store, eventService, taskRepository, executor, jsonSupport);

        assertStatus(() -> disabled.createAndExecute(EVENT_ID, request("idem-1")),
                HttpStatus.FORBIDDEN);
        verify(eventService, never()).requireEvent(any());
        verify(store, never()).create(any());
    }

    @Test
    void unconfirmedRequestIsRejectedWithoutCreatingRecord() {
        CreateRemediationRequest request = new CreateRemediationRequest(
                RemediationActionType.DISABLE_EXPERIMENT_FAULT, TASK_ID, false,
                "idem-1", "operator", null);

        assertStatus(() -> service.createAndExecute(EVENT_ID, request), HttpStatus.BAD_REQUEST);
        verify(store, never()).create(any());
        verify(executor, never()).executeAction(any());
    }

    @Test
    void missingActionTypeIsRejected() {
        CreateRemediationRequest request = new CreateRemediationRequest(
                null, TASK_ID, true, null, null, null);

        assertStatus(() -> service.createAndExecute(EVENT_ID, request), HttpStatus.BAD_REQUEST);
        verify(store, never()).create(any());
    }

    @Test
    void nonExperimentEventIsRejected() {
        event.setSource("MANUAL");

        assertStatus(() -> service.createAndExecute(EVENT_ID, request("idem-1")),
                HttpStatus.CONFLICT);
        verify(store, never()).create(any());
    }

    @Test
    void missingExperimentIdIsRejected() {
        event.setExperimentId(null);

        assertStatus(() -> service.createAndExecute(EVENT_ID, request("idem-1")),
                HttpStatus.CONFLICT);
        verify(store, never()).create(any());
    }

    @Test
    void resolvedEventIsRejected() {
        event.setStatus(FaultEventStatus.RESOLVED);

        assertStatus(() -> service.createAndExecute(EVENT_ID, request("idem-1")),
                HttpStatus.CONFLICT);
        verify(store, never()).create(any());
    }

    @Test
    void missingEventIsRejectedBeforeCreatingRecord() {
        when(eventService.requireEvent(EVENT_ID)).thenThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "故障事件不存在"));

        assertStatus(() -> service.createAndExecute(EVENT_ID, request("idem-1")),
                HttpStatus.NOT_FOUND);
        verify(store, never()).create(any());
    }

    @Test
    void sameKeyWhileExecutingReturnsExistingRecordWithoutRunningAgain() {
        RemediationAction existing = existingAction(
                REMEDIATION_ID, TASK_ID, RemediationStatus.EXECUTING, "idem-1");
        when(store.findByEventAndIdempotencyKey(EVENT_ID, "idem-1"))
                .thenReturn(Optional.of(existing));

        RemediationView view = service.createAndExecute(EVENT_ID, request("idem-1"));

        assertThat(view.status()).isEqualTo(RemediationStatus.EXECUTING);
        verify(store, never()).create(any());
        verify(store, never()).claim(any(), any());
        verify(executor, never()).executeAction(any());
        verify(executor, never()).verify(any());
    }

    @Test
    void missingDiagnosisTaskIsRejected() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());

        assertStatus(() -> service.createAndExecute(EVENT_ID, request("idem-1")),
                HttpStatus.NOT_FOUND);
        verify(store, never()).create(any());
    }

    @Test
    void diagnosisTaskFromAnotherEventIsRejected() {
        diagnosisTask.setFaultEventId(UUID.randomUUID());

        assertStatus(() -> service.createAndExecute(EVENT_ID, request("idem-1")),
                HttpStatus.CONFLICT);
    }

    @Test
    void runningDiagnosisTaskIsRejected() {
        diagnosisTask.setStatus(DiagnosisStatus.RUNNING);

        assertStatus(() -> service.createAndExecute(EVENT_ID, request("idem-1")),
                HttpStatus.CONFLICT);
    }

    @Test
    void diagnosisWithoutRootCauseIsRejected() {
        diagnosisTask.setResultStatus(DiagnosisResultStatus.INSUFFICIENT_EVIDENCE);

        assertStatus(() -> service.createAndExecute(EVENT_ID, request("idem-1")),
                HttpStatus.CONFLICT);
    }

    @Test
    void manualEvidenceTaskIsNotAcceptedAsRootCauseDiagnosis() {
        diagnosisTask.setTriggerSource("MANUAL_EVIDENCE");

        assertStatus(() -> service.createAndExecute(EVENT_ID, request("idem-1")),
                HttpStatus.CONFLICT);
    }

    @Test
    void anotherRunningAgentTaskIsRejected() {
        when(taskRepository.existsByFaultEventIdAndTriggerSourceInAndStatusIn(
                eq(EVENT_ID), any(), any())).thenReturn(true);

        assertStatus(() -> service.createAndExecute(EVENT_ID, request("idem-1")),
                HttpStatus.CONFLICT);
        verify(store, never()).create(any());
    }

    @Test
    void anotherActiveRemediationIsRejected() {
        when(store.findActiveByEvent(EVENT_ID)).thenReturn(Optional.of(existingAction(
                REMEDIATION_ID, TASK_ID, RemediationStatus.EXECUTING, "other-key")));

        assertStatus(() -> service.createAndExecute(EVENT_ID, request("idem-1")),
                HttpStatus.CONFLICT);
        verify(store, never()).create(any());
    }

    @Test
    void priorRecoveryWithPossibleExternalSideEffectRequiresAcknowledgment() {
        RemediationAction prior = recoveredAction(RecoveryCodes.ACTION_UNKNOWN);
        when(store.findLatestByEvent(EVENT_ID)).thenReturn(Optional.of(prior));

        assertThatThrownBy(() -> service.createAndExecute(EVENT_ID, request("idem-1")))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason())
                            .contains(PRIOR_REMEDIATION_ID.toString())
                            .contains(RecoveryCodes.ACTION_UNKNOWN)
                            .contains("recoveryAcknowledged");
                });
        verify(store, never()).create(any());
        verify(executor, never()).executeAction(any());
    }

    @Test
    void priorRecoveryWithNullCodeRequiresAcknowledgment() {
        when(store.findLatestByEvent(EVENT_ID))
                .thenReturn(Optional.of(recoveredAction(null)));

        assertStatus(() -> service.createAndExecute(EVENT_ID, request("idem-1")),
                HttpStatus.CONFLICT);
        verify(store, never()).create(any());
    }

    @Test
    void priorRecoveryWithAcknowledgmentAndNoteProceedsAndIsAudited() throws Exception {
        RemediationAction prior = recoveredAction(RecoveryCodes.VERIFICATION_UNKNOWN);
        when(store.findLatestByEvent(EVENT_ID)).thenReturn(Optional.of(prior));

        RemediationView view = service.createAndExecute(EVENT_ID,
                request("idem-1", true, "已人工核查实验、订单和库存状态"));

        assertThat(view.status()).isEqualTo(RemediationStatus.SUCCEEDED);
        ArgumentCaptor<RemediationAction> captor =
                ArgumentCaptor.forClass(RemediationAction.class);
        verify(store).create(captor.capture());
        JsonNode audit = objectMapper.readTree(captor.getValue().getAuditTrace());
        assertThat(audit.toString())
                .contains("PRIOR_RECOVERY_ACKNOWLEDGED")
                .contains(PRIOR_REMEDIATION_ID.toString())
                .contains(RecoveryCodes.VERIFICATION_UNKNOWN);
    }

    @Test
    void priorRecoveryAcknowledgedWithoutOperatorNoteIsRejected() {
        when(store.findLatestByEvent(EVENT_ID))
                .thenReturn(Optional.of(recoveredAction(RecoveryCodes.ACTION_FAILED)));

        assertStatus(() -> service.createAndExecute(EVENT_ID,
                        request("idem-1", true, "   ")),
                HttpStatus.BAD_REQUEST);
        verify(store, never()).create(any());
    }

    @Test
    void priorRecoveryBeforeActionDoesNotRequireAcknowledgment() throws Exception {
        when(store.findLatestByEvent(EVENT_ID))
                .thenReturn(Optional.of(recoveredAction(RecoveryCodes.BEFORE_ACTION)));

        RemediationView view = service.createAndExecute(EVENT_ID, request("idem-1"));

        assertThat(view.status()).isEqualTo(RemediationStatus.SUCCEEDED);
        ArgumentCaptor<RemediationAction> captor =
                ArgumentCaptor.forClass(RemediationAction.class);
        verify(store).create(captor.capture());
        JsonNode audit = objectMapper.readTree(captor.getValue().getAuditTrace());
        assertThat(audit.toString()).doesNotContain("PRIOR_RECOVERY_ACKNOWLEDGED");
    }

    @Test
    void newIdempotencyKeyAfterSucceededIsRejectedWithoutExternalAction() {
        when(store.hasSucceeded(EVENT_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.createAndExecute(EVENT_ID, request("idem-new")))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).contains("成功且验证通过");
                });
        verify(store, never()).create(any());
        verify(executor, never()).executeAction(any());
        verify(executor, never()).verify(any());
    }

    @Test
    void succeededRecordWithEventStatusNotUpdatedStillBlocksNewKey() {
        // 事件未被更新为 RESOLVED（例如并发诊断把它改回 OPEN）不影响成功事实。
        when(store.hasSucceeded(EVENT_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.createAndExecute(EVENT_ID, request("idem-new")))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).contains("人工核查故障事件状态");
                });
        verify(store, never()).create(any());
        verify(executor, never()).executeAction(any());
        verify(executor, never()).verify(any());
    }

    @Test
    void sameKeyReplayAfterSucceededIsNotBlockedBySucceededGate() {
        RemediationAction existing = existingAction(
                REMEDIATION_ID, TASK_ID, RemediationStatus.SUCCEEDED, "idem-1");
        when(store.hasSucceeded(EVENT_ID)).thenReturn(true);
        when(store.findByEventAndIdempotencyKey(EVENT_ID, "idem-1"))
                .thenReturn(Optional.of(existing));

        RemediationView view = service.createAndExecute(EVENT_ID, request("idem-1"));

        assertThat(view.id()).isEqualTo(REMEDIATION_ID);
        assertThat(view.status()).isEqualTo(RemediationStatus.SUCCEEDED);
        verify(store, never()).create(any());
        verify(executor, never()).executeAction(any());
        verify(executor, never()).verify(any());
    }

    @Test
    void successAppearingBeforeExecuteRecheckStopsBeforeExternalAction() {
        // 第一次前置校验通过；抢占执行权后二次校验发现其他请求已经成功处置。
        when(store.hasSucceeded(EVENT_ID)).thenReturn(false, true);

        RemediationView view = service.createAndExecute(EVENT_ID, request("idem-1"));

        assertThat(view.status()).isEqualTo(RemediationStatus.ACTION_FAILED);
        assertThat(view.failureCode()).isEqualTo("EVENT_ALREADY_REMEDIATED");
        assertThat(view.verificationResult()).isNull();
        verify(executor, never()).executeAction(any());
        verify(executor, never()).verify(any());
    }

    @Test
    void inconclusiveVerificationRequiresManualReview() {
        when(store.findLatestByEvent(EVENT_ID)).thenReturn(Optional.of(priorAction(
                RemediationStatus.VERIFICATION_INCONCLUSIVE, "BUSINESS_REJECTED")));

        assertThatThrownBy(() -> service.createAndExecute(EVENT_ID, request("idem-1")))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason())
                            .contains("VERIFICATION_INCONCLUSIVE")
                            .contains("recoveryAcknowledged");
                });
        verify(store, never()).create(any());
        verify(executor, never()).executeAction(any());
    }

    @Test
    void verificationFailureRequiresManualReview() {
        when(store.findLatestByEvent(EVENT_ID)).thenReturn(Optional.of(priorAction(
                RemediationStatus.VERIFICATION_FAILED, "FAULT_STILL_OBSERVED")));

        assertStatus(() -> service.createAndExecute(EVENT_ID, request("idem-1")),
                HttpStatus.CONFLICT);
        verify(store, never()).create(any());
        verify(executor, never()).executeAction(any());
    }

    @Test
    void manualReviewWithAcknowledgmentProceedsAndIsAudited() throws Exception {
        when(store.findLatestByEvent(EVENT_ID)).thenReturn(Optional.of(priorAction(
                RemediationStatus.VERIFICATION_INCONCLUSIVE, "BUSINESS_REJECTED")));

        RemediationView view = service.createAndExecute(EVENT_ID,
                request("idem-1", true, "已人工核查实验、订单和库存状态"));

        assertThat(view.status()).isEqualTo(RemediationStatus.SUCCEEDED);
        ArgumentCaptor<RemediationAction> captor =
                ArgumentCaptor.forClass(RemediationAction.class);
        verify(store).create(captor.capture());
        JsonNode audit = objectMapper.readTree(captor.getValue().getAuditTrace());
        assertThat(audit.toString())
                .contains("PRIOR_MANUAL_REVIEW_ACKNOWLEDGED")
                .contains(PRIOR_REMEDIATION_ID.toString())
                .contains("BUSINESS_REJECTED");
    }

    @Test
    void ambiguousActionFailureRequiresManualReview() {
        when(store.findLatestByEvent(EVENT_ID)).thenReturn(Optional.of(priorAction(
                RemediationStatus.ACTION_FAILED, "INVENTORY_UNAVAILABLE")));
        assertStatus(() -> service.createAndExecute(EVENT_ID, request("idem-1")),
                HttpStatus.CONFLICT);

        when(store.findLatestByEvent(EVENT_ID)).thenReturn(Optional.of(priorAction(
                RemediationStatus.ACTION_FAILED, null)));
        assertStatus(() -> service.createAndExecute(EVENT_ID, request("idem-2")),
                HttpStatus.CONFLICT);
        verify(store, never()).create(any());
        verify(executor, never()).executeAction(any());
    }

    @Test
    void clearActionFailureCanBeRetriedWithoutExtraReview() throws Exception {
        when(store.findLatestByEvent(EVENT_ID)).thenReturn(Optional.of(priorAction(
                RemediationStatus.ACTION_FAILED, "TARGET_EXPERIMENT_MISMATCH")));

        RemediationView view = service.createAndExecute(EVENT_ID, request("idem-1"));

        assertThat(view.status()).isEqualTo(RemediationStatus.SUCCEEDED);
        ArgumentCaptor<RemediationAction> captor =
                ArgumentCaptor.forClass(RemediationAction.class);
        verify(store).create(captor.capture());
        JsonNode audit = objectMapper.readTree(captor.getValue().getAuditTrace());
        assertThat(audit.toString()).doesNotContain("PRIOR_MANUAL_REVIEW_ACKNOWLEDGED");
    }

    @Test
    void sameIdempotencyKeyForRecoveredRecordReplaysWithoutAcknowledgment() {
        RemediationAction recovered = recoveredAction(RecoveryCodes.ACTION_UNKNOWN);
        recovered.setIdempotencyKey("idem-1");
        when(store.findByEventAndIdempotencyKey(EVENT_ID, "idem-1"))
                .thenReturn(Optional.of(recovered));
        when(store.findLatestByEvent(EVENT_ID)).thenReturn(Optional.of(recovered));

        RemediationView view = service.createAndExecute(EVENT_ID, request("idem-1"));

        assertThat(view.id()).isEqualTo(PRIOR_REMEDIATION_ID);
        assertThat(view.status()).isEqualTo(RemediationStatus.RECOVERY_REQUIRED);
        verify(store, never()).create(any());
        verify(executor, never()).executeAction(any());
        verify(executor, never()).verify(any());
    }

    @Test
    void sameIdempotencyKeyReturnsExistingRecordEvenAfterEventResolved() {
        event.setStatus(FaultEventStatus.RESOLVED);
        RemediationAction existing = existingAction(
                REMEDIATION_ID, TASK_ID, RemediationStatus.SUCCEEDED, "idem-1");
        when(store.findByEventAndIdempotencyKey(EVENT_ID, "idem-1"))
                .thenReturn(Optional.of(existing));

        RemediationView view = service.createAndExecute(EVENT_ID, request("idem-1"));

        assertThat(view.id()).isEqualTo(REMEDIATION_ID);
        assertThat(view.status()).isEqualTo(RemediationStatus.SUCCEEDED);
        verify(store, never()).create(any());
        verify(executor, never()).executeAction(any());
        verify(executor, never()).verify(any());
    }

    @Test
    void sameIdempotencyKeyWithDifferentContentIsRejected() {
        UUID otherTask = UUID.randomUUID();
        RemediationAction existing = existingAction(
                REMEDIATION_ID, otherTask, RemediationStatus.SUCCEEDED, "idem-1");
        when(store.findByEventAndIdempotencyKey(EVENT_ID, "idem-1"))
                .thenReturn(Optional.of(existing));

        assertStatus(() -> service.createAndExecute(EVENT_ID, request("idem-1")),
                HttpStatus.CONFLICT);
        verify(executor, never()).executeAction(any());
    }

    @Test
    void createRaceWithSameKeyReturnsCommittedExistingRecord() {
        RemediationAction existing = existingAction(
                REMEDIATION_ID, TASK_ID, RemediationStatus.EXECUTING, "idem-1");
        when(store.findByEventAndIdempotencyKey(EVENT_ID, "idem-1"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        // 覆盖已有存根必须用 doThrow：when(mock.create(any())) 会先执行 setUp 中
        // 已配置的 Answer，而此时 any() 传入的是 null，造成测试自身 NPE。
        doThrow(new DataIntegrityViolationException("duplicate")).when(store).create(any());

        RemediationView view = service.createAndExecute(EVENT_ID, request("idem-1"));

        assertThat(view.id()).isEqualTo(REMEDIATION_ID);
        verify(executor, never()).executeAction(any());
        verify(executor, never()).verify(any());
    }

    @Test
    void createRaceWithDifferentKeyIsRejectedByActiveUniqueness() {
        RemediationAction active = existingAction(
                UUID.randomUUID(), TASK_ID, RemediationStatus.EXECUTING, "other-key");
        when(store.findActiveByEvent(EVENT_ID))
                .thenReturn(Optional.empty(), Optional.of(active));
        doThrow(new DataIntegrityViolationException("duplicate")).when(store).create(any());

        assertStatus(() -> service.createAndExecute(EVENT_ID, request("idem-1")),
                HttpStatus.CONFLICT);
        verify(executor, never()).executeAction(any());
        verify(executor, never()).verify(any());
    }

    @Test
    void successfulRemediationUsesEventTargetAndRunsOneVerification() {
        RemediationView view = service.createAndExecute(EVENT_ID, request("idem-1"));

        assertThat(view.status()).isEqualTo(RemediationStatus.SUCCEEDED);
        assertThat(view.eventStatusUpdated()).isTrue();
        assertThat(view.experimentId()).isEqualTo(EXPERIMENT_ID);
        assertThat(view.actionResult()).isNotNull();
        assertThat(view.verificationResult()).isNotNull();

        ArgumentCaptor<RemediationAction> captor = ArgumentCaptor.forClass(RemediationAction.class);
        verify(store).create(captor.capture());
        assertThat(captor.getValue().getExperimentId()).isEqualTo(EXPERIMENT_ID);
        assertThat(captor.getValue().getTargetService()).isEqualTo("inventory-service");
        assertThat(captor.getValue().getRequestedBy()).isEqualTo("operator");
        verify(executor).executeAction(event);
        verify(executor).verify(event);
        verify(store).finishSucceeded(any(), eq(EVENT_ID), any(), any(), any(), any(), any(), any());
    }

    @Test
    void actionFailureKeepsActionResultAndNeverVerifies() {
        when(executor.executeAction(event)).thenReturn(actionFailure());

        RemediationView view = service.createAndExecute(EVENT_ID, request("idem-1"));

        assertThat(view.status()).isEqualTo(RemediationStatus.ACTION_FAILED);
        assertThat(view.failureCode()).isEqualTo("TARGET_EXPERIMENT_MISMATCH");
        assertThat(view.actionResult().path("success").asBoolean()).isFalse();
        assertThat(view.verificationResult()).isNull();
        verify(executor, never()).verify(any());
        verify(store, never()).finishSucceeded(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void verificationFailureKeepsActionSuccessAndVerificationFailure() {
        when(executor.verify(event)).thenReturn(verificationFailure());

        RemediationView view = service.createAndExecute(EVENT_ID, request("idem-1"));

        assertThat(view.status()).isEqualTo(RemediationStatus.VERIFICATION_FAILED);
        assertThat(view.failureCode()).isEqualTo("FAULT_STILL_OBSERVED");
        assertThat(view.actionResult().path("success").asBoolean()).isTrue();
        assertThat(view.verificationResult().path("result").asText()).isEqualTo("FAIL");
        assertThat(view.eventStatusUpdated()).isFalse();
        verify(store, never()).finishSucceeded(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void inconclusiveVerificationMapsToInconclusiveStatus() {
        when(executor.verify(event)).thenReturn(verificationInconclusive());

        RemediationView view = service.createAndExecute(EVENT_ID, request("idem-1"));

        assertThat(view.status()).isEqualTo(RemediationStatus.VERIFICATION_INCONCLUSIVE);
        assertThat(view.failureCode()).isEqualTo("BUSINESS_REJECTED");
    }

    @Test
    void verificationSuccessButEventUpdateFailureStillReportsSucceeded() {
        // 同样必须用 doAnswer 覆盖 setUp 中的存根，否则配置存根时会执行旧 Answer。
        doAnswer(invocation -> {
            RemediationAction action = persisted.get();
            action.setStatus(RemediationStatus.SUCCEEDED);
            action.setActionResult(invocation.<String>getArgument(2));
            action.setVerificationResult(invocation.<String>getArgument(3));
            action.setAuditTrace(invocation.<String>getArgument(4));
            action.setEventStatusUpdated(false);
            action.setCompletedAt(invocation.getArgument(7));
            return new FinishResult(true, false);
        }).when(store).finishSucceeded(
                any(), any(), any(), any(), any(), any(), any(), any());

        RemediationView view = service.createAndExecute(EVENT_ID, request("idem-1"));

        assertThat(view.status()).isEqualTo(RemediationStatus.SUCCEEDED);
        assertThat(view.eventStatusUpdated()).isFalse();
    }

    @Test
    void claimFailureDoesNotExecuteActionAgain() {
        when(store.claim(any(), any())).thenReturn(false);

        service.createAndExecute(EVENT_ID, request("idem-1"));

        verify(executor, never()).executeAction(any());
        verify(executor, never()).verify(any());
    }

    @Test
    void listAndQueriesUsePersistedRecords() {
        RemediationAction existing = existingAction(
                REMEDIATION_ID, TASK_ID, RemediationStatus.SUCCEEDED, "idem-1");
        when(store.listByEvent(EVENT_ID)).thenReturn(List.of(existing));
        when(store.findById(REMEDIATION_ID)).thenReturn(Optional.of(existing));
        when(store.findByEventAndIdempotencyKey(EVENT_ID, "idem-1"))
                .thenReturn(Optional.of(existing));

        assertThat(service.list(EVENT_ID)).hasSize(1);
        assertThat(service.get(REMEDIATION_ID)).isNotNull();
        assertThat(service.getByEventAndIdempotencyKey(EVENT_ID, "idem-1").id())
                .isEqualTo(REMEDIATION_ID);
    }

    @Test
    void queryByUnknownIdOrKeyReturnsNotFound() {
        when(store.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertStatus(() -> service.get(UUID.randomUUID()), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.getByEventAndIdempotencyKey(EVENT_ID, "missing"),
                HttpStatus.NOT_FOUND);
    }

    private CreateRemediationRequest request(String key) {
        return request(key, false, "confirmed by test");
    }

    private CreateRemediationRequest request(String key,
                                             boolean recoveryAcknowledged,
                                             String operatorNote) {
        return new CreateRemediationRequest(
                RemediationActionType.DISABLE_EXPERIMENT_FAULT,
                TASK_ID,
                true,
                key,
                "operator",
                operatorNote,
                recoveryAcknowledged);
    }

    private RemediationAction recoveredAction(String failureCode) {
        RemediationAction action = existingAction(
                PRIOR_REMEDIATION_ID, TASK_ID, RemediationStatus.RECOVERY_REQUIRED, "prior-key");
        action.setFailureCode(failureCode);
        action.setFailureReason("interrupted by restart");
        return action;
    }

    private RemediationAction priorAction(RemediationStatus status, String failureCode) {
        RemediationAction action = existingAction(
                PRIOR_REMEDIATION_ID, TASK_ID, status, "prior-key");
        action.setFailureCode(failureCode);
        action.setFailureReason("prior terminal state");
        return action;
    }

    private FaultEvent event(FaultEventStatus status, String source, UUID experimentId) {
        FaultEvent event = new FaultEvent();
        event.setId(EVENT_ID);
        event.setStatus(status);
        event.setSource(source);
        event.setExperimentId(experimentId);
        event.setDetails("{\"sku\":\"SKU-1001\",\"quantity\":1}");
        return event;
    }

    private DiagnosisTask task(UUID id,
                               UUID eventId,
                               String triggerSource,
                               DiagnosisStatus status,
                               DiagnosisResultStatus resultStatus) {
        DiagnosisTask task = new DiagnosisTask();
        task.setId(id);
        task.setFaultEventId(eventId);
        task.setTriggerSource(triggerSource);
        task.setStatus(status);
        task.setResultStatus(resultStatus);
        return task;
    }

    private RemediationAction existingAction(UUID id,
                                             UUID taskId,
                                             RemediationStatus status,
                                             String key) {
        RemediationAction action = new RemediationAction();
        action.setId(id);
        action.setFaultEventId(EVENT_ID);
        action.setDiagnosisTaskId(taskId);
        action.setActionType(RemediationActionType.DISABLE_EXPERIMENT_FAULT);
        action.setTargetService("inventory-service");
        action.setExperimentId(EXPERIMENT_ID);
        action.setStatus(status);
        action.setRequestedBy("operator");
        action.setIdempotencyKey(key);
        action.setRequestedAt(Instant.now());
        return action;
    }

    private ActionOutcome actionSuccess() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("outcome", "DISABLED");
        result.put("observedExperimentIdBefore", EXPERIMENT_ID.toString());
        result.put("activeAfter", false);
        return new ActionOutcome(true, "DISABLED", null, null, result);
    }

    private ActionOutcome actionFailure() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("outcome", "TARGET_EXPERIMENT_MISMATCH");
        result.put("failureCode", "TARGET_EXPERIMENT_MISMATCH");
        return new ActionOutcome(false, "TARGET_EXPERIMENT_MISMATCH",
                "TARGET_EXPERIMENT_MISMATCH", "目标实验不匹配", result);
    }

    private VerificationOutcome verificationPass() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attempted", true);
        result.put("result", "PASS");
        result.put("verified", true);
        result.put("httpStatus", 200);
        result.put("outcome", "SUCCESS");
        result.put("faultObserved", false);
        return new VerificationOutcome(true, "PASS", true, null, null,
                result, "exp-verify-1", null);
    }

    private VerificationOutcome verificationFailure() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attempted", true);
        result.put("result", "FAIL");
        result.put("verified", false);
        result.put("httpStatus", 503);
        result.put("outcome", "DOWNSTREAM_HTTP_5XX");
        result.put("faultObserved", true);
        return new VerificationOutcome(true, "FAIL", false, "FAULT_STILL_OBSERVED",
                "验证请求仍观察到故障", result, "exp-verify-2", null);
    }

    private VerificationOutcome verificationInconclusive() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attempted", true);
        result.put("result", "INCONCLUSIVE");
        result.put("verified", false);
        result.put("httpStatus", 409);
        result.put("outcome", "BUSINESS_CONFLICT");
        result.put("faultObserved", false);
        return new VerificationOutcome(true, "INCONCLUSIVE", false, "BUSINESS_REJECTED",
                "库存冲突，无法证明恢复", result, "exp-verify-3", null);
    }

    private void assertStatus(ThrowingCall call, HttpStatus expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(expected));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
