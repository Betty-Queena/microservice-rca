package com.microservice.rca.inventory.fault;

import java.util.UUID;

/**
 * 可选的条件关闭参数。提供 expectedExperimentId 时，只允许关闭匹配的当前活动实验；
 * 不提供时保持原有“关闭当前故障”的兼容行为。
 */
public record DisableFaultRequest(UUID expectedExperimentId) {
}
