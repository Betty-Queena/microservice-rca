package com.microservice.rca.external.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microservice.rca.external.config.ExternalServiceProperties;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SSRF 边界测试：只允许 http/https、本地回环或字面 IP、白名单端口，禁止 userinfo 与路径穿越。
 */
class ExternalServiceUrlValidatorTest {

    private final ExternalServiceUrlValidator validator = new ExternalServiceUrlValidator(
            new ExternalServiceProperties(
                    List.of("localhost", "127.0.0.1"),
                    // 8084 用于验证同源限制（两个端口都在白名单内，但主机/端口必须一致）。
                    List.of(8083, 8084),
                    2000,
                    5000,
                    262144,
                    "/actuator/health",
                    "/actuator/metrics",
                    true));

    @Test
    void acceptsWhitelistedLoopbackAddress() {
        assertThat(validator.normalizeBaseUrl("http://localhost:8083/"))
                .isEqualTo("http://localhost:8083");
        assertThat(validator.normalizeBaseUrl("http://127.0.0.1:8083"))
                .isEqualTo("http://127.0.0.1:8083");
    }

    @Test
    void rejectsUnsupportedScheme() {
        assertThatThrownBy(() -> validator.validateBaseUrl("ftp://localhost:8083"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只允许 http 或 https");
    }

    @Test
    void rejectsUserInfoAndQuery() {
        assertThatThrownBy(() -> validator.validateBaseUrl("http://user:pass@localhost:8083"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户名或密码");
        assertThatThrownBy(() -> validator.validateBaseUrl("http://localhost:8083?target=x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("查询参数");
    }

    @Test
    void rejectsPlainHostnameAndUnlistedPort() {
        assertThatThrownBy(() -> validator.validateBaseUrl("http://example.com:8083"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字面 IP");
        assertThatThrownBy(() -> validator.validateBaseUrl("http://localhost:9999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("端口不在白名单");
    }

    @Test
    void rejectsPathTraversalAndEncodedTraversal() {
        assertThatThrownBy(() -> validator.validateBaseUrl("http://localhost:8083/a/../b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("..");
        assertThatThrownBy(() -> validator.validateBaseUrl("http://localhost:8083/%2e%2e/admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("编码后的穿越字符");
    }

    @Test
    void endpointPathMustBeAbsoluteAndClean() {
        assertThat(validator.normalizeEndpointPath(null, "/actuator/health"))
                .isEqualTo("/actuator/health");
        assertThatThrownBy(() -> validator.normalizeEndpointPath("actuator/health", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须以 / 开头");
        assertThatThrownBy(() -> validator.normalizeEndpointPath("/actuator//health", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("连续斜杠");
    }

    @Test
    void logSourceMustBeSameOrigin() {
        URI base = validator.validateBaseUrl("http://localhost:8083");
        assertThat(validator.normalizeLogSourceUrl("http://localhost:8083/api/logs", base))
                .isEqualTo("http://localhost:8083/api/logs");
        assertThatThrownBy(() ->
                validator.normalizeLogSourceUrl("http://localhost:8084/api/logs", base))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同源");
    }

    @Test
    void rejectsReservedServiceName() {
        assertThatThrownBy(() -> validator.normalizeName("all"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("保留名称");
    }

    @Test
    void resolveKeepsBasePathInsteadOfReplacingIt() {
        URI base = validator.validateBaseUrl("http://localhost:8083/petclinic");
        assertThat(validator.resolve(base, "/actuator/health").toString())
                .isEqualTo("http://localhost:8083/petclinic/actuator/health");
    }
}
