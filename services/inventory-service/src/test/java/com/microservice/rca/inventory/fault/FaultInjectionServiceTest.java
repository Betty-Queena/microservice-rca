package com.microservice.rca.inventory.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
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
