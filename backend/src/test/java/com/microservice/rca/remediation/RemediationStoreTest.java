package com.microservice.rca.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microservice.rca.diagnosis.service.FaultEventService;
import com.microservice.rca.remediation.domain.RemediationAction;
import com.microservice.rca.remediation.domain.RemediationStatus;
import com.microservice.rca.remediation.repository.RemediationActionRepository;
import com.microservice.rca.remediation.service.RemediationStore;
import com.microservice.rca.remediation.service.RemediationStore.FinishResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RemediationStoreTest {

    private static final UUID REMEDIATION_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();

    private final RemediationActionRepository repository =
            mock(RemediationActionRepository.class);
    private final FaultEventService faultEventService = mock(FaultEventService.class);
    private final RemediationStore store = new RemediationStore(repository, faultEventService);

    @Test
    void createFlushesImmediatelyToSurfaceUniqueConflicts() {
        RemediationAction action = new RemediationAction();
        when(repository.saveAndFlush(action)).thenReturn(action);

        assertThat(store.create(action)).isSameAs(action);
        verify(repository).saveAndFlush(action);
    }

    @Test
    void claimUsesConditionalPendingToExecutingUpdate() {
        when(repository.markExecuting(any(), any(), any(), any())).thenReturn(1);

        assertThat(store.claim(REMEDIATION_ID, Instant.now())).isTrue();
        verify(repository).markExecuting(
                eq(REMEDIATION_ID),
                eq(RemediationStatus.PENDING),
                eq(RemediationStatus.EXECUTING),
                any(Instant.class));
    }

    @Test
    void actionResultIsPersistedBeforeVerification() {
        when(repository.recordActionResult(any(), any(), any(), any())).thenReturn(1);

        assertThat(store.recordAction(REMEDIATION_ID, "{\"success\":true}", "[]")).isTrue();
        verify(repository).recordActionResult(
                REMEDIATION_ID, RemediationStatus.EXECUTING,
                "{\"success\":true}", "[]");
    }

    @Test
    void successfulVerificationResolvesOpenEventAndMarksFlag() {
        when(repository.finishIfExecuting(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyBoolean(), any())).thenReturn(1);
        when(faultEventService.resolveIfOpen(EVENT_ID)).thenReturn(true);
        when(repository.markEventStatusUpdated(
                any(), any(), anyBoolean())).thenReturn(1);

        FinishResult result = store.finishSucceeded(
                REMEDIATION_ID, EVENT_ID, "{\"success\":true}", "{\"verified\":true}",
                "[]", "exp-1", null, Instant.now());

        assertThat(result.terminalUpdated()).isTrue();
        assertThat(result.eventStatusUpdated()).isTrue();
        verify(faultEventService).resolveIfOpen(EVENT_ID);
        verify(repository).markEventStatusUpdated(
                REMEDIATION_ID, RemediationStatus.SUCCEEDED, true);
    }

    @Test
    void successfulVerificationDoesNotOverwriteHumanResolvedEvent() {
        when(repository.finishIfExecuting(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyBoolean(), any())).thenReturn(1);
        when(faultEventService.resolveIfOpen(EVENT_ID)).thenReturn(false);

        FinishResult result = store.finishSucceeded(
                REMEDIATION_ID, EVENT_ID, "{}", "{\"verified\":true}",
                "[]", "exp-1", null, Instant.now());

        assertThat(result.terminalUpdated()).isTrue();
        assertThat(result.eventStatusUpdated()).isFalse();
        verify(repository, never()).markEventStatusUpdated(any(), any(), anyBoolean());
    }

    @Test
    void alreadyTerminalRemediationIsNotFinishedTwice() {
        when(repository.finishIfExecuting(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyBoolean(), any())).thenReturn(0);

        FinishResult result = store.finishSucceeded(
                REMEDIATION_ID, EVENT_ID, "{}", "{}", "[]", null, null, Instant.now());

        assertThat(result.terminalUpdated()).isFalse();
        assertThat(result.eventStatusUpdated()).isFalse();
        verify(faultEventService, never()).resolveIfOpen(any());
    }

    @Test
    void failedVerificationDoesNotTouchEventStatus() {
        when(repository.finishIfExecuting(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyBoolean(), any())).thenReturn(1);

        boolean finished = store.finishFailed(
                REMEDIATION_ID,
                RemediationStatus.VERIFICATION_FAILED,
                "FAULT_STILL_OBSERVED",
                "still failing",
                "{\"success\":true}",
                "{\"verified\":false}",
                "[]",
                "exp-1",
                null,
                Instant.now());

        assertThat(finished).isTrue();
        verify(faultEventService, never()).resolveIfOpen(any());
    }

    @Test
    void hasSucceededOnlyQueriesSucceededStatus() {
        when(repository.existsByFaultEventIdAndStatus(
                EVENT_ID, RemediationStatus.SUCCEEDED)).thenReturn(true);

        assertThat(store.hasSucceeded(EVENT_ID)).isTrue();
        verify(repository).existsByFaultEventIdAndStatus(
                EVENT_ID, RemediationStatus.SUCCEEDED);
    }

    @Test
    void findLatestByEventDelegatesToRequestedAtOrdering() {
        RemediationAction action = new RemediationAction();
        when(repository.findFirstByFaultEventIdOrderByRequestedAtDesc(EVENT_ID))
                .thenReturn(Optional.of(action));

        assertThat(store.findLatestByEvent(EVENT_ID)).contains(action);
        verify(repository).findFirstByFaultEventIdOrderByRequestedAtDesc(EVENT_ID);
    }

    @Test
    void findInterruptedBeforeOnlyQueriesActiveStatuses() {
        Instant cutoff = Instant.parse("2026-10-24T10:00:00Z");
        List<RemediationAction> actions = List.of(new RemediationAction());
        when(repository.findInterruptedBefore(
                List.of(RemediationStatus.PENDING, RemediationStatus.EXECUTING), cutoff))
                .thenReturn(actions);

        assertThat(store.findInterruptedBefore(cutoff)).isSameAs(actions);
        verify(repository).findInterruptedBefore(
                List.of(RemediationStatus.PENDING, RemediationStatus.EXECUTING), cutoff);
    }

    @Test
    void markInterruptedDelegatesStatusAndVersionCas() {
        Instant completedAt = Instant.parse("2026-10-24T10:01:00Z");
        when(repository.markInterrupted(
                REMEDIATION_ID,
                RemediationStatus.EXECUTING,
                3L,
                RemediationStatus.RECOVERY_REQUIRED,
                "INTERRUPTED_VERIFICATION_UNKNOWN",
                "verification result unknown",
                "[]",
                completedAt)).thenReturn(1);

        assertThat(store.markInterrupted(
                REMEDIATION_ID,
                RemediationStatus.EXECUTING,
                3L,
                RemediationStatus.RECOVERY_REQUIRED,
                "INTERRUPTED_VERIFICATION_UNKNOWN",
                "verification result unknown",
                "[]",
                completedAt)).isTrue();

        verify(repository).markInterrupted(
                REMEDIATION_ID,
                RemediationStatus.EXECUTING,
                3L,
                RemediationStatus.RECOVERY_REQUIRED,
                "INTERRUPTED_VERIFICATION_UNKNOWN",
                "verification result unknown",
                "[]",
                completedAt);
    }
}
