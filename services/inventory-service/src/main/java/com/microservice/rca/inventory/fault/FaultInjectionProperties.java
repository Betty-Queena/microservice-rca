package com.microservice.rca.inventory.fault;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 故障注入实验开关与安全边界。默认关闭，仅允许配置中的本地地址访问。
 */
@ConfigurationProperties(prefix = "experiment.fault-injection")
public class FaultInjectionProperties {

    private boolean enabled = false;
    private List<String> allowedRemoteAddresses = new ArrayList<>(
            List.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1"));
    private int maxDelayMs = 4500;
    private int defaultDurationSeconds = 120;
    private int maxDurationSeconds = 600;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAllowedRemoteAddresses() {
        return allowedRemoteAddresses;
    }

    public void setAllowedRemoteAddresses(List<String> allowedRemoteAddresses) {
        this.allowedRemoteAddresses = allowedRemoteAddresses;
    }

    public int getMaxDelayMs() {
        return maxDelayMs;
    }

    public void setMaxDelayMs(int maxDelayMs) {
        this.maxDelayMs = maxDelayMs;
    }

    public int getDefaultDurationSeconds() {
        return defaultDurationSeconds;
    }

    public void setDefaultDurationSeconds(int defaultDurationSeconds) {
        this.defaultDurationSeconds = defaultDurationSeconds;
    }

    public int getMaxDurationSeconds() {
        return maxDurationSeconds;
    }

    public void setMaxDurationSeconds(int maxDurationSeconds) {
        this.maxDurationSeconds = maxDurationSeconds;
    }
}
