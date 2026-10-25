package com.microservice.rca.inventory.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class FaultInjectionServiceTest {

    @Test
    void enableIsRejectedWhenExperimentSwitchIsOff() {
        FaultInjectionService service = new FaultInjectionService(properties(false));

        assertThatThrownBy(() -> service.enable(new EnableFaultRequest(FaultType.DELAY, 1000, 60, "test")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void enabledServiceActivatesDelayFaultWithExpiry() {
        FaultInjectionService service = new FaultInjectionService(properties(true));

        FaultInjectionStatus status = service.enable(
                new EnableFaultRequest(FaultType.DELAY, 3500, 60, " 慢响应 "));

        assertThat(status.experimentEnabled()).isTrue();
        assertThat(status.active()).isTrue();
        assertThat(status.experimentId()).isNotBlank();
        assertThat(status.faultType()).isEqualTo("DELAY");
        assertThat(status.delayMs()).isEqualTo(3500);
        assertThat(status.note()).isEqualTo("慢响应");
        assertThat(status.expiresAt()).isAfter(status.activatedAt());
        assertThat(status.remainingMs()).isPositive();
    }

    @Test
    void http500FaultIgnoresDelayParameter() {
        FaultInjectionService service = new FaultInjectionService(properties(true));

        FaultInjectionStatus status = service.enable(
                new EnableFaultRequest(FaultType.HTTP_500, 999, 60, null));

        assertThat(status.faultType()).isEqualTo("HTTP_500");
        assertThat(status.delayMs()).isNull();
    }

    @Test
    void defaultDelayUpperBoundIsBelowOrderServiceReadTimeout() {
        FaultInjectionProperties properties = new FaultInjectionProperties();
        properties.setEnabled(true);
        FaultInjectionService service = new FaultInjectionService(properties);

        assertThat(properties.getMaxDelayMs()).isEqualTo(4500);
        assertThat(service.enable(new EnableFaultRequest(FaultType.DELAY, 4500, 60, null)).delayMs())
                .isEqualTo(4500);
        assertThatThrownBy(() -> service.enable(new EnableFaultRequest(FaultType.DELAY, 4501, 60, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("delayMs 必须在 1 到 4500 之间");
    }

    @Test
    void delayMustBeWithinConfiguredBounds() {
        FaultInjectionService service = new FaultInjectionService(properties(true));

        assertThatThrownBy(() -> service.enable(new EnableFaultRequest(FaultType.DELAY, null, 60, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("delayMs 必须在 1 到 5000 之间");
        assertThatThrownBy(() -> service.enable(new EnableFaultRequest(FaultType.DELAY, 0, 60, null)))
                .hasMessageContaining("delayMs 必须在 1 到 5000 之间");
        assertThatThrownBy(() -> service.enable(new EnableFaultRequest(FaultType.DELAY, 5001, 60, null)))
                .hasMessageContaining("delayMs 必须在 1 到 5000 之间");
    }

    @Test
    void durationMustBeWithinConfiguredBounds() {
        FaultInjectionService service = new FaultInjectionService(properties(true));

        assertThatThrownBy(() -> service.enable(new EnableFaultRequest(FaultType.HTTP_500, null, 0, null)))
                .hasMessageContaining("durationSeconds 必须在 1 到 300 之间");
        assertThatThrownBy(() -> service.enable(new EnableFaultRequest(FaultType.HTTP_500, null, 301, null)))
                .hasMessageContaining("durationSeconds 必须在 1 到 300 之间");
    }

    @Test
    void disableClearsActiveFault() {
        FaultInjectionService service = new FaultInjectionService(properties(true));
        service.enable(new EnableFaultRequest(FaultType.DELAY, 1000, 60, null));

        FaultInjectionStatus status = service.disable();

        assertThat(status.active()).isFalse();
        assertThat(status.experimentId()).isNull();
        assertThat(service.activeState()).isNull();
    }

    @Test
    void conditionalDisableClosesMatchingExperiment() {
        FaultInjectionService service = new FaultInjectionService(properties(true));
        FaultInjectionStatus enabled = service.enable(
                new EnableFaultRequest(FaultType.HTTP_500, null, 60, null));

        FaultInjectionStatus status = service.disable(UUID.fromString(enabled.experimentId()));

        assertThat(status.active()).isFalse();
        assertThat(status.experimentId()).isNull();
        assertThat(service.activeState()).isNull();
    }

    @Test
    void conditionalDisableRejectsMismatchedExperimentWithoutClearingFault() {
        FaultInjectionService service = new FaultInjectionService(properties(true));
        FaultInjectionStatus enabled = service.enable(
                new EnableFaultRequest(FaultType.DELAY, 1000, 60, null));

        assertThatThrownBy(() -> service.disable(UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException conflict = (ResponseStatusException) ex;
                    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(conflict.getReason()).contains("实验 ID 与当前活动故障不一致");
                });

        FaultInjectionStatus current = service.status();
        assertThat(current.active()).isTrue();
        assertThat(current.experimentId()).isEqualTo(enabled.experimentId());
    }

    @Test
    void conditionalDisableRejectsWhenNoFaultIsActive() {
        FaultInjectionService service = new FaultInjectionService(properties(true));

        assertThatThrownBy(() -> service.disable(UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException conflict = (ResponseStatusException) ex;
                    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(conflict.getReason()).contains("当前没有活动故障注入");
                });

        assertThat(service.status().active()).isFalse();
    }

    @Test
    void concurrentConditionalDisableOnlySucceedsOnce() throws Exception {
        FaultInjectionService service = new FaultInjectionService(properties(true));
        FaultInjectionStatus enabled = service.enable(
                new EnableFaultRequest(FaultType.HTTP_500, null, 60, null));
        UUID experimentId = UUID.fromString(enabled.experimentId());
        int attempts = 8;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        return false;
                    }
                    try {
                        service.disable(experimentId);
                        return true;
                    } catch (ResponseStatusException ex) {
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                        return false;
                    }
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successes = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(service.status().active()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredFaultIsAutomaticallyCleared() {
        FaultInjectionService service = new FaultInjectionService(properties(true));
        Instant now = Instant.now();
        FaultInjectionState expired = new FaultInjectionState(
                "expired-experiment", FaultType.DELAY, 1000,
                now.minusSeconds(120), now.minusSeconds(60), "已过期");
        ReflectionTestUtils.setField(service, "active", new AtomicReference<>(expired));

        assertThat(service.activeState()).isNull();
        assertThat(service.status().active()).isFalse();
    }

    private FaultInjectionProperties properties(boolean enabled) {
        FaultInjectionProperties properties = new FaultInjectionProperties();
        properties.setEnabled(enabled);
        properties.setMaxDelayMs(5000);
        properties.setDefaultDurationSeconds(60);
        properties.setMaxDurationSeconds(300);
        return properties;
    }
}
