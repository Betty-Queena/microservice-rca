package com.microservice.rca.diagnosis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.rca.common.JsonSupport;
import com.microservice.rca.diagnosis.domain.FaultEventStatus;
import com.microservice.rca.diagnosis.repository.DiagnosisTaskRepository;
import com.microservice.rca.diagnosis.repository.FaultEventRepository;
import com.microservice.rca.diagnosis.repository.ToolInvocationRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FaultEventServiceTest {

    private final FaultEventRepository eventRepository = mock(FaultEventRepository.class);
    private final FaultEventService service = new FaultEventService(
            eventRepository,
            mock(DiagnosisTaskRepository.class),
            mock(ToolInvocationRepository.class),
            new JsonSupport(new ObjectMapper().findAndRegisterModules()));

    @Test
    void restoresOnlyWhenEventIsStillDiagnosing() {
        UUID eventId = UUID.randomUUID();
        when(eventRepository.updateStatusIfCurrent(
                eq(eventId), eq(FaultEventStatus.DIAGNOSING), eq(FaultEventStatus.OPEN),
                any(Instant.class)))
                .thenReturn(1);

        assertThat(service.restoreOpenIfDiagnosing(eventId)).isTrue();
        verify(eventRepository).updateStatusIfCurrent(
                eq(eventId), eq(FaultEventStatus.DIAGNOSING), eq(FaultEventStatus.OPEN),
                any(Instant.class));
    }

    @Test
    void reportsNoChangeWhenEventWasResolvedConcurrently() {
        UUID eventId = UUID.randomUUID();
        when(eventRepository.updateStatusIfCurrent(
                eq(eventId), eq(FaultEventStatus.DIAGNOSING), eq(FaultEventStatus.OPEN),
                any(Instant.class)))
                .thenReturn(0);

        assertThat(service.restoreOpenIfDiagnosing(eventId)).isFalse();
    }
}
