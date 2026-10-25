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

    /**
     * 兼容旧调用的无条件关闭。Day 8 的受控处置不调用该重载，而是使用带
     * expectedExperimentId 的条件关闭。
     */
    public FaultInjectionStatus disable() {
        active.set(null);
        return status();
    }

    /**
     * 仅当当前活动实验 ID 与期望值一致时关闭故障。
     *
     * <p>通过 compareAndSet 完成条件删除，避免“先读取再无条件清空”导致并发场景下
     * 误关其他实验。没有活动故障或 ID 不匹配时返回 409，且不修改当前状态。</p>
     */
    public FaultInjectionStatus disable(UUID expectedExperimentId) {
        if (expectedExperimentId == null) {
            return disable();
        }
        while (true) {
            FaultInjectionState state = active.get();
            if (state == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "当前没有活动故障注入，无法按实验 ID 关闭");
            }
            if (!state.activeAt(Instant.now())) {
                if (active.compareAndSet(state, null)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "当前没有活动故障注入，无法按实验 ID 关闭");
                }
                // 过期状态被并发替换，重新读取后再判断。
                continue;
            }
            if (!expectedExperimentId.toString().equals(state.experimentId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "实验 ID 与当前活动故障不一致: expected=" + expectedExperimentId
                                + ", active=" + state.experimentId());
            }
            if (active.compareAndSet(state, null)) {
                return status();
            }
            // 状态在读取后被并发修改，重新读取后再判断，不能直接清空。
        }
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
