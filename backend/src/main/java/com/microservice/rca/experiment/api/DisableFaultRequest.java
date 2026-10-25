package com.microservice.rca.experiment.api;

import java.util.UUID;

/**
 * 可选的故障关闭条件。用于受控处置，确保只关闭故障事件对应的实验；
 * 不提供 expectedExperimentId 时保持原有无条件关闭行为。
 */
public record DisableFaultRequest(UUID expectedExperimentId) {
}
