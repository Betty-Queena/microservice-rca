package com.microservice.rca.remediation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 受控处置开关。默认关闭，只有显式设置 rca.remediation.enabled=true 才允许创建和执行处置。
 */
@ConfigurationProperties(prefix = "rca.remediation")
public record RemediationProperties(boolean enabled) {
}
