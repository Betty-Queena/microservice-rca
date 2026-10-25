package com.microservice.rca.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.rca.common.JsonSupport;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.experiment.ExperimentService;
import com.microservice.rca.experiment.api.CheckoutRequest;
import com.microservice.rca.experiment.api.ExperimentExecutionView;
import com.microservice.rca.experiment.api.ExperimentFaultStatusView;
import com.microservice.rca.remediation.service.RemediationExecutor;
import com.microservice.rca.remediation.service.RemediationExecutor.ActionOutcome;
import com.microservice.rca.remediation.service.RemediationExecutor.VerificationOutcome;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RemediationExecutorTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID EXPERIMENT_ID = UUID.randomUUID();

    private final ExperimentService experimentService = mock(ExperimentService.class);
    private final JsonSupport jsonSupport = new JsonSupport(
            new ObjectMapper().findAndRegisterModules());
    private final RemediationExecutor executor = new RemediationExecutor(
            experimentService, jsonSupport, 3000);

    @BeforeEach
    void defaultStubs() {
        when(experimentService.status()).thenReturn(status(false, null));
    }

    @Test
    void matchingActiveFaultUsesConditionalDisableOnly() {
        when(experimentService.status()).thenReturn(status(true, EXPERIMENT_ID));
        when(experimentService.disable(EXPERIMENT_ID)).thenReturn(status(false, null));

        ActionOutcome outcome = executor.executeAction(event("{}"));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.outcome()).isEqualTo("DISABLED");
        assertThat(outcome.actionResult()).containsEntry("activeBefore", true);
        assertThat(outcome.actionResult()).containsEntry("activeAfter", false);
        verify(experimentService).disable(EXPERIMENT_ID);
        verify(experimentService, never()).disable();
        verify(experimentService, never()).reset();
    }

    @Test
    void mismatchedActiveExperimentIsNotClosed() {
        UUID otherExperiment = UUID.randomUUID();
        when(experimentService.status()).thenReturn(status(true, otherExperiment));

        ActionOutcome outcome = executor.executeAction(event("{}"));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.failureCode()).isEqualTo("TARGET_EXPERIMENT_MISMATCH");
        verify(experimentService, never()).disable(any(UUID.class));
        verify(experimentService, never()).disable();
    }

    @Test
    void conditionalDisableConflictMarksActionFailed() {
        when(experimentService.status()).thenReturn(status(true, EXPERIMENT_ID));
        when(experimentService.disable(EXPERIMENT_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                        "实验 ID 与当前活动故障不一致"));

        ActionOutcome outcome = executor.executeAction(event("{}"));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.failureCode()).isEqualTo("FAULT_DISABLE_CONFLICT");
    }

    @Test
    void alreadyInactiveFaultStillCountsAsSuccessfulAction() {
        when(experimentService.status()).thenReturn(status(false, null));

        ActionOutcome outcome = executor.executeAction(event("{}"));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.outcome()).isEqualTo("ALREADY_INACTIVE");
        verify(experimentService, never()).disable(any(UUID.class));
    }

    @Test
    void inventoryUnavailableMarksActionFailed() {
        when(experimentService.status()).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接库存服务"));

        ActionOutcome outcome = executor.executeAction(event("{}"));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.failureCode()).isEqualTo("INVENTORY_UNAVAILABLE");
    }

    @Test
    void verificationUsesPersistedDetailAndPassesOnlyForCleanSuccess() {
        when(experimentService.checkout(any(CheckoutRequest.class)))
                .thenReturn(execution("SUCCESS", false, 200, null));

        VerificationOutcome outcome = executor.verify(
                event("{\"sku\":\"SKU-1001\",\"quantity\":2}"));

        assertThat(outcome.verified()).isTrue();
        assertThat(outcome.result()).isEqualTo("PASS");
        ArgumentCaptor<CheckoutRequest> captor = ArgumentCaptor.forClass(CheckoutRequest.class);
        verify(experimentService).checkout(captor.capture());
        assertThat(captor.getValue().sku()).isEqualTo("SKU-1001");
        assertThat(captor.getValue().quantity()).isEqualTo(2);
        assertThat(outcome.verificationResult()).containsEntry("verified", true);
        assertThat(outcome.verificationResult()).containsEntry("faultObserved", false);
    }

    @Test
    void verificationWithObservedFaultFails() {
        when(experimentService.checkout(any(CheckoutRequest.class)))
                .thenReturn(execution("SLOW_RESPONSE", true, 200, null));

        VerificationOutcome outcome = executor.verify(event(details()));

        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.result()).isEqualTo("FAIL");
        assertThat(outcome.failureCode()).isEqualTo("FAULT_STILL_OBSERVED");
    }

    @Test
    void verificationThatCreatesFaultEventFails() {
        UUID newEventId = UUID.randomUUID();
        when(experimentService.checkout(any(CheckoutRequest.class)))
                .thenReturn(execution("DOWNSTREAM_HTTP_5XX", true, 503, newEventId));

        VerificationOutcome outcome = executor.verify(event(details()));

        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.result()).isEqualTo("FAIL");
        assertThat(outcome.faultEventId()).isEqualTo(newEventId);
        assertThat(outcome.verificationResult()).containsEntry("faultEventId", newEventId);
    }

    @Test
    void businessConflictIsInconclusiveNotSuccessOrFailure() {
        when(experimentService.checkout(any(CheckoutRequest.class)))
                .thenReturn(execution("BUSINESS_CONFLICT", false, 409, null));

        VerificationOutcome outcome = executor.verify(event(details()));

        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.result()).isEqualTo("INCONCLUSIVE");
        assertThat(outcome.failureCode()).isEqualTo("BUSINESS_REJECTED");
    }

    @Test
    void missingVerificationInputDoesNotCallCheckout() {
        VerificationOutcome outcome = executor.verify(event("{\"sku\":\"SKU-1001\"}"));

        assertThat(outcome.attempted()).isFalse();
        assertThat(outcome.result()).isEqualTo("INCONCLUSIVE");
        assertThat(outcome.failureCode()).isEqualTo("VERIFICATION_INPUT_MISSING");
        verify(experimentService, never()).checkout(any(CheckoutRequest.class));
    }

    @Test
    void unreachableOrderServiceFailsVerification() {
        when(experimentService.checkout(any(CheckoutRequest.class)))
                .thenReturn(execution("ORDER_SERVICE_UNREACHABLE", true, 0, null));

        VerificationOutcome outcome = executor.verify(event(details()));

        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.result()).isEqualTo("FAIL");
        assertThat(outcome.failureCode()).isEqualTo("ORDER_SERVICE_UNREACHABLE");
    }

    private FaultEvent event(String details) {
        FaultEvent event = new FaultEvent();
        event.setId(EVENT_ID);
        event.setExperimentId(EXPERIMENT_ID);
        event.setDetails(details);
        return event;
    }

    private String details() {
        return "{\"sku\":\"SKU-1001\",\"quantity\":1}";
    }

    private ExperimentFaultStatusView status(boolean active, UUID experimentId) {
        return new ExperimentFaultStatusView(
                true,
                active,
                experimentId != null ? experimentId.toString() : null,
                active ? "HTTP_500" : null,
                null,
                null,
                null,
                null,
                120,
                4500,
                600,
                null,
                "test",
                Instant.now());
    }

    private ExperimentExecutionView execution(String outcome,
                                              boolean faultObserved,
                                              Integer httpStatus,
                                              UUID faultEventId) {
        return new ExperimentExecutionView(
                EXPERIMENT_ID,
                "exp-verification-1",
                "HTTP_500",
                outcome,
                faultObserved,
                httpStatus,
                120,
                null,
                "CREATED",
                "order-1",
                49,
                null,
                faultEventId,
                Instant.now(),
                null);
    }
}
