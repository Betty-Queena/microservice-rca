package com.microservice.rca.inventory.fault;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 故障注入状态管理。故障只保存在实验服务内存中，默认关闭，并设置自动过期时间，
 * 不修改任何业务数据或数据库结构。
 */
@Service
public class FaultInjectionService {

    private final AtomicReference<FaultInjectionState> active = new AtomicReference<>();
    private final FaultInjectionProperties properties;

    public FaultInjectionService(FaultInjectionProperties properties) {
        this.properties = properties;
    }

    public FaultInjectionStatus status() {
        FaultInjectionState state = activeState();
        if (state == null) {
            return new FaultInjectionStatus(
                    properties.isEnabled(), false, null, null, null,
                    null, null, null,
                    properties.getDefaultDurationSeconds(),
                    properties.getMaxDelayMs(),
                    properties.getMaxDurationSeconds(),
                    null);
        }
        Instant now = Instant.now();
        return new FaultInjectionStatus(
                properties.isEnabled(), true, state.experimentId(), state.faultType().name(),
                state.delayMs(), state.activatedAt(), state.expiresAt(), state.remainingMs(now),
                properties.getDefaultDurationSeconds(), properties.getMaxDelayMs(),
                properties.getMaxDurationSeconds(), state.note());
    }

    public FaultInjectionStatus enable(EnableFaultRequest request) {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "故障注入默认禁用，请设置 EXPERIMENT_FAULT_INJECTION_ENABLED=true 后重启库存服务");
        }
        Integer delayMs = validateDelay(request.faultType(), request.delayMs());
        int durationSeconds = validateDuration(request.durationSeconds());
        Instant now = Instant.now();
        FaultInjectionState state = new FaultInjectionState(
                UUID.randomUUID().toString(),
                request.faultType(),
                delayMs,
                now,
                now.plusSeconds(durationSeconds),
                trimNote(request.note()));
        active.set(state);
        return status();
    }

    public FaultInjectionStatus disable() {
        active.set(null);
        return status();
    }

    public FaultInjectionStatus reset() {
        active.set(null);
        return status();
    }

    /**
     * 返回当前激活状态；过期后自动清除，避免故障永久生效。
     */
    public FaultInjectionState activeState() {
        FaultInjectionState state = active.get();
        if (state != null && !state.activeAt(Instant.now())) {
            active.compareAndSet(state, null);
            return null;
        }
        return state;
    }

    private Integer validateDelay(FaultType type, Integer delayMs) {
        if (type == FaultType.DELAY) {
            if (delayMs == null || delayMs < 1 || delayMs > properties.getMaxDelayMs()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "delayMs 必须在 1 到 " + properties.getMaxDelayMs() + " 之间");
            }
            return delayMs;
        }
        return null;
    }

    private int validateDuration(Integer durationSeconds) {
        int duration = durationSeconds != null
                ? durationSeconds
                : properties.getDefaultDurationSeconds();
        if (duration < 1 || duration > properties.getMaxDurationSeconds()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "durationSeconds 必须在 1 到 " + properties.getMaxDurationSeconds() + " 之间");
        }
        return duration;
    }

    private String trimNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        return note.trim();
    }
}
