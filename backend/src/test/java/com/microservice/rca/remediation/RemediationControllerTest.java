package com.microservice.rca.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.rca.common.GlobalExceptionHandler;
import com.microservice.rca.remediation.api.CreateRemediationRequest;
import com.microservice.rca.remediation.api.RemediationView;
import com.microservice.rca.remediation.domain.RecoveryCodes;
import com.microservice.rca.remediation.domain.RemediationActionType;
import com.microservice.rca.remediation.domain.RemediationStatus;
import com.microservice.rca.remediation.service.RemediationService;
import com.microservice.rca.remediation.web.RemediationController;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class RemediationControllerTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID REMEDIATION_ID = UUID.randomUUID();

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RemediationService remediationService = mock(RemediationService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RemediationController(remediationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createReturnsAcceptedTerminalStatusAsHttp200() throws Exception {
        when(remediationService.createAndExecute(eq(EVENT_ID), any()))
                .thenReturn(view(RemediationStatus.SUCCEEDED));

        mockMvc.perform(post("/api/fault-events/{eventId}/remediations", EVENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        ArgumentCaptor<CreateRemediationRequest> captor =
                ArgumentCaptor.forClass(CreateRemediationRequest.class);
        verify(remediationService).createAndExecute(eq(EVENT_ID), captor.capture());
        assertThat(captor.getValue().confirmed()).isTrue();
    }

    @Test
    void disabledSwitchMapsTo403() throws Exception {
        when(remediationService.createAndExecute(eq(EVENT_ID), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "受控处置默认关闭"));

        mockMvc.perform(post("/api/fault-events/{eventId}/remediations", EVENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void missingConfirmationIsRejectedByValidationBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/api/fault-events/{eventId}/remediations", EVENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(remediationService, never()).createAndExecute(any(), any());
    }

    @Test
    void malformedJsonReturns400Not500() throws Exception {
        mockMvc.perform(post("/api/fault-events/{eventId}/remediations", EVENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(remediationService, never()).createAndExecute(any(), any());
    }

    @Test
    void invalidUuidInBodyReturns400Not500() throws Exception {
        String body = """
                {
                  "actionType": "DISABLE_EXPERIMENT_FAULT",
                  "diagnosisTaskId": "not-a-uuid",
                  "confirmed": true
                }
                """;

        mockMvc.perform(post("/api/fault-events/{eventId}/remediations", EVENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void invalidUuidInPathReturns400Not500() throws Exception {
        mockMvc.perform(post("/api/fault-events/not-a-uuid/remediations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void listReturnsHistory() throws Exception {
        when(remediationService.list(EVENT_ID))
                .thenReturn(List.of(view(RemediationStatus.SUCCEEDED)));

        mockMvc.perform(get("/api/fault-events/{eventId}/remediations", EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(REMEDIATION_ID.toString()));
    }

    @Test
    void getByIdAndIdempotencyKeyAreAvailableAfterTimeout() throws Exception {
        RemediationView view = view(RemediationStatus.SUCCEEDED);
        when(remediationService.get(REMEDIATION_ID)).thenReturn(view);
        when(remediationService.getByEventAndIdempotencyKey(EVENT_ID, "idem-1"))
                .thenReturn(view);

        mockMvc.perform(get("/api/remediations/{remediationId}", REMEDIATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(REMEDIATION_ID.toString()));

        mockMvc.perform(get("/api/fault-events/{eventId}/remediations/by-idempotency-key/{key}",
                        EVENT_ID, "idem-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(REMEDIATION_ID.toString()));
    }

    @Test
    void invalidRemediationIdReturns400Not500() throws Exception {
        mockMvc.perform(get("/api/remediations/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void missingRecordReturns404() throws Exception {
        when(remediationService.get(REMEDIATION_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "处置记录不存在"));

        mockMvc.perform(get("/api/remediations/{remediationId}", REMEDIATION_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void createPassesRecoveryAcknowledgmentToService() throws Exception {
        when(remediationService.createAndExecute(eq(EVENT_ID), any()))
                .thenReturn(recoveryView());
        CreateRemediationRequest request = new CreateRemediationRequest(
                RemediationActionType.DISABLE_EXPERIMENT_FAULT,
                TASK_ID,
                true,
                "idem-1",
                "local-operator",
                "已人工核查实验、订单和库存状态",
                true);

        mockMvc.perform(post("/api/fault-events/{eventId}/remediations", EVENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.data.failureCode")
                        .value(RecoveryCodes.VERIFICATION_UNKNOWN));

        ArgumentCaptor<CreateRemediationRequest> captor =
                ArgumentCaptor.forClass(CreateRemediationRequest.class);
        verify(remediationService).createAndExecute(eq(EVENT_ID), captor.capture());
        assertThat(captor.getValue().recoveryAcknowledged()).isTrue();
    }

    @Test
    void recoveryDetailsAreVisibleThroughAllQueryInterfaces() throws Exception {
        RemediationView view = recoveryView();
        when(remediationService.get(REMEDIATION_ID)).thenReturn(view);
        when(remediationService.list(EVENT_ID)).thenReturn(List.of(view));
        when(remediationService.getByEventAndIdempotencyKey(EVENT_ID, "idem-1"))
                .thenReturn(view);

        mockMvc.perform(get("/api/remediations/{remediationId}", REMEDIATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.data.failureCode")
                        .value(RecoveryCodes.VERIFICATION_UNKNOWN))
                .andExpect(jsonPath("$.data.failureReason").value(
                        "验证请求可能已执行，结果未落库"))
                .andExpect(jsonPath("$.data.auditTrace[0].step").value("RECOVERY_MARKED"))
                .andExpect(jsonPath("$.data.auditTrace[0].manualReviewRequired").value(true));

        mockMvc.perform(get("/api/fault-events/{eventId}/remediations", EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("RECOVERY_REQUIRED"));

        mockMvc.perform(get("/api/fault-events/{eventId}/remediations/by-idempotency-key/{key}",
                        EVENT_ID, "idem-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(REMEDIATION_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("RECOVERY_REQUIRED"));
    }

    private String validBody(Boolean confirmed) throws Exception {
        CreateRemediationRequest request = new CreateRemediationRequest(
                RemediationActionType.DISABLE_EXPERIMENT_FAULT,
                TASK_ID,
                confirmed,
                "idem-1",
                "local-operator",
                "manual review");
        return objectMapper.writeValueAsString(request);
    }

    private RemediationView recoveryView() {
        JsonNode actionResult = objectMapper.createObjectNode().put("success", true);
        JsonNode auditTrace = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("step", "RECOVERY_MARKED")
                        .put("manualReviewRequired", true)
                        .put("recoveryCode", RecoveryCodes.VERIFICATION_UNKNOWN));
        Instant now = Instant.now();
        return new RemediationView(
                REMEDIATION_ID,
                EVENT_ID,
                TASK_ID,
                RemediationActionType.DISABLE_EXPERIMENT_FAULT,
                "inventory-service",
                UUID.randomUUID(),
                RemediationStatus.RECOVERY_REQUIRED,
                RecoveryCodes.VERIFICATION_UNKNOWN,
                "验证请求可能已执行，结果未落库",
                "local-operator",
                "已人工核查",
                "idem-1",
                actionResult,
                null,
                auditTrace,
                null,
                null,
                false,
                now.minusSeconds(30),
                now.minusSeconds(20),
                now);
    }

    private RemediationView view(RemediationStatus status) {
        JsonNode actionResult = objectMapper.createObjectNode().put("success", true);
        JsonNode verificationResult = objectMapper.createObjectNode().put("verified", true);
        JsonNode auditTrace = objectMapper.createArrayNode();
        Instant now = Instant.now();
        return new RemediationView(
                REMEDIATION_ID,
                EVENT_ID,
                TASK_ID,
                RemediationActionType.DISABLE_EXPERIMENT_FAULT,
                "inventory-service",
                UUID.randomUUID(),
                status,
                null,
                null,
                "local-operator",
                "manual review",
                "idem-1",
                actionResult,
                verificationResult,
                auditTrace,
                "exp-verify-1",
                null,
                status == RemediationStatus.SUCCEEDED,
                now,
                now,
                now);
    }
}
