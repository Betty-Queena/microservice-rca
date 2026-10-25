package com.microservice.rca.external.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 外部服务接入的安全与超时参数。
 *
 * <p>默认只允许本机回环地址和少量开发端口。显式授权其他地址时，
 * 白名单中必须填写字面 IP，不允许普通域名，以避免 DNS 重绑定绕过限制。</p>
 */
@ConfigurationProperties(prefix = "rca.external-services")
public record ExternalServiceProperties(
        List<String> allowedHosts,
        List<Integer> allowedPorts,
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxResponseBytes,
        String defaultHealthPath,
        String defaultMetricsPath,
        Boolean logSourceSameOriginOnly) {

    public ExternalServiceProperties {
        allowedHosts = allowedHosts == null || allowedHosts.isEmpty()
                ? List.of("localhost", "127.0.0.1", "::1")
                : List.copyOf(allowedHosts);
        allowedPorts = allowedPorts == null || allowedPorts.isEmpty()
                ? List.of(80, 443, 8080, 8081, 8082, 8083, 8084, 8085, 9090)
                : List.copyOf(allowedPorts);
        connectTimeoutMs = connectTimeoutMs <= 0 ? 2000 : Math.min(connectTimeoutMs, 10000);
        readTimeoutMs = readTimeoutMs <= 0 ? 5000 : Math.min(readTimeoutMs, 30000);
        maxResponseBytes = maxResponseBytes <= 0 ? 262144 : Math.min(maxResponseBytes, 1048576);
        defaultHealthPath = blank(defaultHealthPath) ? "/actuator/health" : defaultHealthPath.trim();
        defaultMetricsPath = blank(defaultMetricsPath) ? "/actuator/metrics" : defaultMetricsPath.trim();
        logSourceSameOriginOnly = logSourceSameOriginOnly == null || logSourceSameOriginOnly;
    }

    public boolean sameOriginLogSourceOnly() {
        return Boolean.TRUE.equals(logSourceSameOriginOnly);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
