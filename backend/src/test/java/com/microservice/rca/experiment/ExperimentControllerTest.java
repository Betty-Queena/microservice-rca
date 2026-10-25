package com.microservice.rca.experiment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.microservice.rca.common.GlobalExceptionHandler;
import com.microservice.rca.experiment.api.ExperimentFaultStatusView;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class ExperimentControllerTest {

    private ExperimentService experimentService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        experimentService = mock(ExperimentService.class);
        when(experimentService.disable()).thenReturn(statusView());
        when(experimentService.disable(any(UUID.class))).thenReturn(statusView());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ExperimentController(experimentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void disableWithoutBodyUsesLegacyMethod() throws Exception {
        mockMvc.perform(post("/api/experiments/faults/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(experimentService).disable();
        verify(experimentService, never()).disable(any(UUID.class));
    }

    @Test
    void disableWithExpectedExperimentIdUsesConditionalMethod() throws Exception {
        UUID experimentId = UUID.randomUUID();

        mockMvc.perform(post("/api/experiments/faults/disable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedExperimentId\":\"" + experimentId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(experimentService).disable(experimentId);
        verify(experimentService, never()).disable();
    }

    @Test
    void conditionalDisableConflictIsNotWrappedAsSuccess() throws Exception {
        UUID experimentId = UUID.randomUUID();
        when(experimentService.disable(experimentId))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.CONFLICT, "实验 ID 与当前活动故障不一致"));

        mockMvc.perform(post("/api/experiments/faults/disable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedExperimentId\":\"" + experimentId + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("实验 ID 与当前活动故障不一致"));
    }

    private ExperimentFaultStatusView statusView() {
        return new ExperimentFaultStatusView(
                true, false, null, null, null, null, null, null,
                120, 4500, 600, null, "inventory-service:/api/experiments/faults",
                Instant.now());
    }
}
