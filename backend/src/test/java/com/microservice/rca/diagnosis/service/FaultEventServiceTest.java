package com.microservice.rca.diagnosis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.rca.common.JsonSupport;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.domain.FaultEventStatus;
import com.microservice.rca.diagnosis.repository.DiagnosisTaskRepository;
import com.microservice.rca.diagnosis.repository.FaultEventRepository;
import com.microservice.rca.diagnosis.repository.ToolInvocationRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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

    @Test
    void rejectsManualTransitionToDiagnosing() {
        UUID eventId = UUID.randomUUID();
        FaultEvent event = event(eventId, FaultEventStatus.OPEN, null);
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.updateStatus(eventId, FaultEventStatus.DIAGNOSING))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(eventRepository, never()).save(any(FaultEvent.class));
    }

    @Test
    void markDiagnosingUsesConditionalUpdate() {
        UUID eventId = UUID.randomUUID();
        when(eventRepository.updateStatusIfCurrent(
                eq(eventId), eq(FaultEventStatus.OPEN), eq(FaultEventStatus.DIAGNOSING),
                any(Instant.class)))
                .thenReturn(1);

        assertThat(service.markDiagnosing(eventId)).isTrue();
        verify(eventRepository).updateStatusIfCurrent(
                eq(eventId), eq(FaultEventStatus.OPEN), eq(FaultEventStatus.DIAGNOSING),
                any(Instant.class));
    }

    @Test
    void resolveIfOpenUsesConditionalUpdateWithResolvedAt() {
        UUID eventId = UUID.randomUUID();
        when(eventRepository.resolveIfOpen(
                eq(eventId), eq(FaultEventStatus.OPEN), eq(FaultEventStatus.RESOLVED),
                any(Instant.class)))
                .thenReturn(1);

        assertThat(service.resolveIfOpen(eventId)).isTrue();
        verify(eventRepository).resolveIfOpen(
                eq(eventId), eq(FaultEventStatus.OPEN), eq(FaultEventStatus.RESOLVED),
                any(Instant.class));
    }

    @Test
    void resolveIfOpenDoesNotOverwriteNonOpenEvent() {
        UUID eventId = UUID.randomUUID();
        when(eventRepository.resolveIfOpen(
                eq(eventId), eq(FaultEventStatus.OPEN), eq(FaultEventStatus.RESOLVED),
                any(Instant.class)))
                .thenReturn(0);

        assertThat(service.resolveIfOpen(eventId)).isFalse();
    }

    @Test
    void setsResolvedAtOnlyWhenResolvingWithoutExistingValue() {
        UUID eventId = UUID.randomUUID();
        FaultEvent event = event(eventId, FaultEventStatus.OPEN, null);
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(eventRepository.save(any(FaultEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.updateStatus(eventId, FaultEventStatus.RESOLVED);

        assertThat(event.getStatus()).isEqualTo(FaultEventStatus.RESOLVED);
        assertThat(event.getResolvedAt()).isNotNull();
    }

    @Test
    void closingDoesNotOverwriteExistingResolvedAt() {
        UUID eventId = UUID.randomUUID();
        Instant resolvedAt = Instant.parse("2026-10-20T10:00:00Z");
        FaultEvent event = event(eventId, FaultEventStatus.RESOLVED, resolvedAt);
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(eventRepository.save(any(FaultEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.updateStatus(eventId, FaultEventStatus.CLOSED);

        assertThat(event.getStatus()).isEqualTo(FaultEventStatus.CLOSED);
        assertThat(event.getResolvedAt()).isEqualTo(resolvedAt);
    }

    private FaultEvent event(UUID id, FaultEventStatus status, Instant resolvedAt) {
        FaultEvent event = new FaultEvent();
        event.setId(id);
        event.setStatus(status);
        event.setResolvedAt(resolvedAt);
        return event;
    }
}
