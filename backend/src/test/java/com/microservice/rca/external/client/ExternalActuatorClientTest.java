package com.microservice.rca.external.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.rca.external.config.ExternalServiceProperties;
import com.microservice.rca.external.domain.ExternalService;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 外部 Actuator 证据解析测试：不发起真实网络请求，验证不可用状态和字段清洗。
 */
class ExternalActuatorClientTest {

    private final ExternalServiceHttpClient httpClient = mock(ExternalServiceHttpClient.class);
    private final ExternalActuatorClient client = new ExternalActuatorClient(
            validator(), httpClient, new ObjectMapper());

    @Test
    void logsAreExplicitlyUnavailableWithoutConfiguredSource() {
        ExternalService service = service("http://localhost:8083", null);

        ExternalActuatorClient.LogSnapshot snapshot = client.logs(service, null, 50);

        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.message()).contains("未配置日志数据源");
        verifyNoInteractions(httpClient);
    }

    @Test
    void metricsAreExplicitlyUnavailableWithoutConfiguredPath() {
        ExternalService service = service("http://localhost:8083", null);

        ExternalActuatorClient.MetricsSnapshot snapshot = client.metrics(service);

        assertThat(snapshot.available()).isFalse();
        assertThat(snapshot.message()).contains("未配置指标端点");
        verifyNoInteractions(httpClient);
    }

    @Test
    void healthParsesHttp503ComponentsAndRedactsSensitiveDetails() {
        when(httpClient.get(any(URI.class))).thenReturn(new ExternalServiceHttpClient.HttpResult(
                503,
                """
                        {"status":"DOWN","components":{
                          "db":{"status":"DOWN","details":{"error":"org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC Connection jdbc:h2:tcp://localhost:9092/./petclinicdb user=sa password=secret token=abc Bearer abc.def postgresql://rca:pgsecret@db:5432/rca C:\\\\Users\\\\y\\\\sensitive-dir C:/Users/y/sensitive-dir /home/y/sensitive-dir"}},
                          "diskSpace":{"status":"UP"},
                          "outer":{"status":"DOWN","components":{"inner":{"status":"UP"}}}
                        }}
                        """,
                false,
                3013));

        ExternalActuatorClient.HealthSnapshot snapshot = client.health(service(
                "http://localhost:8083", null));

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.status()).isEqualTo("DOWN");
        assertThat(snapshot.httpStatus()).isEqualTo(503);
        assertThat(snapshot.latencyMs()).isEqualTo(3013);
        assertThat(snapshot.components()).extracting(ExternalActuatorClient.HealthComponent::path)
                .containsExactly("db", "diskSpace", "outer", "outer.inner");
        ExternalActuatorClient.HealthComponent db = component(snapshot, "db");
        assertThat(db.status()).isEqualTo("DOWN");
        assertThat(db.errorType()).contains("CannotGetJdbcConnectionException");
        assertThat(db.errorSummary()).contains("Failed to obtain JDBC Connection");
        assertThat(db.errorSummary()).doesNotContain("jdbc:h2", "localhost:9092", "user=sa",
                "password=secret", "token=abc", "abc.def", "pgsecret", "sensitive-dir");
        assertThat(db.errorSummary()).contains("[REDACTED_PATH]");
        assertThat(component(snapshot, "diskSpace").status()).isEqualTo("UP");
        assertThat(component(snapshot, "outer.inner").status()).isEqualTo("UP");
        assertThat(snapshot.message()).doesNotContain("components").doesNotContain("db");
    }

    @Test
    void healthUsesHttpStatusWhenRootStatusIsMissing() {
        when(httpClient.get(any(URI.class))).thenReturn(new ExternalServiceHttpClient.HttpResult(
                503,
                "{\"components\":{\"db\":{\"status\":\"DOWN\","
                        + "\"details\":{\"error\":\"java.sql.SQLException: connection failed\"}}}}",
                false,
                15));

        ExternalActuatorClient.HealthSnapshot snapshot = client.health(service(
                "http://localhost:8083", null));

        assertThat(snapshot.status()).isEqualTo("DOWN");
        assertThat(snapshot.components()).extracting(ExternalActuatorClient.HealthComponent::path)
                .containsExactly("db");
        assertThat(component(snapshot, "db").status()).isEqualTo("DOWN");
    }

    @Test
    void healthParsesHttp200AllComponentsUp() {
        when(httpClient.get(any(URI.class))).thenReturn(new ExternalServiceHttpClient.HttpResult(
                200,
                "{\"status\":\"UP\",\"components\":{\"db\":{\"status\":\"UP\"},"
                        + "\"diskSpace\":{\"status\":\"UP\"}}}",
                false,
                12));

        ExternalActuatorClient.HealthSnapshot snapshot = client.health(service(
                "http://localhost:8083", null));

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.status()).isEqualTo("UP");
        assertThat(snapshot.httpStatus()).isEqualTo(200);
        assertThat(snapshot.components()).extracting(ExternalActuatorClient.HealthComponent::path)
                .containsExactly("db", "diskSpace");
        assertThat(snapshot.components()).allSatisfy(component ->
                assertThat(component.status()).isEqualTo("UP"));
    }

    @Test
    void healthHandlesMissingComponentsInvalidJsonAndOverlongErrors() {
        when(httpClient.get(any(URI.class))).thenReturn(new ExternalServiceHttpClient.HttpResult(
                200, "{\"status\":\"UP\"}", false, 8));

        ExternalActuatorClient.HealthSnapshot noComponents = client.health(service(
                "http://localhost:8083", null));
        assertThat(noComponents.available()).isTrue();
        assertThat(noComponents.components()).isEmpty();

        when(httpClient.get(any(URI.class))).thenReturn(new ExternalServiceHttpClient.HttpResult(
                503, "not-json", false, 20));
        ExternalActuatorClient.HealthSnapshot invalidJson = client.health(service(
                "http://localhost:8083", null));
        assertThat(invalidJson.available()).isTrue();
        assertThat(invalidJson.status()).isEqualTo("DOWN");
        assertThat(invalidJson.components()).isEmpty();
        assertThat(invalidJson.message()).contains("HTTP 503");

        String longError = "java.lang.RuntimeException: " + "x".repeat(2000);
        when(httpClient.get(any(URI.class))).thenReturn(new ExternalServiceHttpClient.HttpResult(
                503,
                "{\"status\":\"DOWN\",\"components\":{\"db\":{\"status\":\"DOWN\","
                        + "\"details\":{\"error\":\"" + longError + "\"}}}}",
                false,
                20));
        ExternalActuatorClient.HealthSnapshot overlong = client.health(service(
                "http://localhost:8083", null));
        ExternalActuatorClient.HealthComponent db = component(overlong, "db");
        assertThat(db.errorType()).isEqualTo("java.lang.RuntimeException");
        assertThat(db.errorSummary()).hasSizeLessThanOrEqualTo(256);
    }

    @Test
    void healthLimitsComponentCountAndErrorDetails() {
        StringBuilder body = new StringBuilder("{\"status\":\"DOWN\",\"components\":{");
        for (int i = 0; i < 25; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("\"c").append(i).append("\":{\"status\":\"DOWN\","
                    + "\"details\":{\"error\":\"java.lang.RuntimeException: failure-")
                    .append(i).append("\"}}");
        }
        body.append("}}");
        when(httpClient.get(any(URI.class))).thenReturn(
                new ExternalServiceHttpClient.HttpResult(503, body.toString(), false, 30));

        ExternalActuatorClient.HealthSnapshot snapshot = client.health(service(
                "http://localhost:8083", null));

        assertThat(snapshot.components()).hasSize(20);
        assertThat(snapshot.componentsTruncated()).isTrue();
        assertThat(snapshot.omittedComponentCount()).isEqualTo(5);
        assertThat(snapshot.components().stream()
                .filter(ExternalActuatorClient.HealthComponent::detailsOmitted)
                .toList())
                .hasSize(15);
    }

    @Test
    void metricsPrioritizeHikariAndKeepCoreMetrics() {
        when(httpClient.get(any(URI.class))).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            if ("/actuator/metrics".equals(uri.getPath())) {
                return new ExternalServiceHttpClient.HttpResult(
                        200,
                        "{\"names\":[\"http.server.requests\",\"jvm.memory.used\","
                                + "\"jvm.memory.max\",\"jvm.gc.pause\",\"process.cpu.usage\","
                                + "\"system.cpu.usage\",\"jvm.threads.live\","
                                + "\"jvm.classes.loaded\",\"hikaricp.connections.timeout\","
                                + "\"hikaricp.connections.acquire\",\"hikaricp.connections.active\","
                                + "\"hikaricp.connections.pending\",\"jdbc.connections.active\"]}",
                        false,
                        4);
            }
            String name = uri.getPath().substring("/actuator/metrics/".length());
            return new ExternalServiceHttpClient.HttpResult(
                    200,
                    "{\"name\":\"" + name + "\",\"measurements\":["
                            + "{\"statistic\":\"COUNT\",\"value\":1}]}",
                    false,
                    6);
        });
        ExternalService service = service("http://localhost:8083", null);
        service.setMetricsPath("/actuator/metrics");

        ExternalActuatorClient.MetricsSnapshot snapshot = client.metrics(service);

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.metrics()).extracting(ExternalActuatorClient.MetricSample::name)
                .startsWith("hikaricp.connections.timeout", "hikaricp.connections.acquire",
                        "hikaricp.connections.active", "hikaricp.connections.pending")
                .contains("http.server.requests", "jvm.memory.used");
        assertThat(snapshot.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("采样上限"));
    }

    @Test
    void metricsDegradeWhenHikariIsAbsent() {
        when(httpClient.get(any(URI.class))).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            if ("/actuator/metrics".equals(uri.getPath())) {
                return new ExternalServiceHttpClient.HttpResult(
                        200,
                        "{\"names\":[\"http.server.requests\",\"jvm.memory.used\"]}",
                        false,
                        4);
            }
            String name = uri.getPath().substring("/actuator/metrics/".length());
            return new ExternalServiceHttpClient.HttpResult(
                    200,
                    "{\"name\":\"" + name + "\",\"measurements\":["
                            + "{\"statistic\":\"VALUE\",\"value\":2}]}",
                    false,
                    6);
        });
        ExternalService service = service("http://localhost:8083", null);
        service.setMetricsPath("/actuator/metrics");

        ExternalActuatorClient.MetricsSnapshot snapshot = client.metrics(service);

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.metrics()).extracting(ExternalActuatorClient.MetricSample::name)
                .containsExactly("http.server.requests", "jvm.memory.used");
        assertThat(snapshot.metrics()).noneMatch(metric ->
                metric.name().startsWith("hikaricp.") || metric.name().startsWith("jdbc."));
    }

    @Test
    void nullLogFieldIsDroppedWithoutLosingTheBatch() {
        when(httpClient.get(any(URI.class))).thenReturn(new ExternalServiceHttpClient.HttpResult(
                200,
                "[{\"id\":\"a-1\",\"message\":null,\"level\":\"ERROR\"}]",
                false,
                5));
        ExternalService service = service("http://localhost:8083", "http://localhost:8083/api/logs");

        ExternalActuatorClient.LogSnapshot snapshot = client.logs(service, null, 10);

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.entries()).hasSize(1);
        assertThat(snapshot.entries().get(0).fields())
                .containsEntry("level", "ERROR")
                .doesNotContainKey("message");
        assertThat(snapshot.entries().get(0).evidenceId()).isEqualTo("log:petclinic:a-1");
    }

    @Test
    void metricsOnlyReadPreferredSamples() {
        when(httpClient.get(any(URI.class))).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            if ("/actuator/metrics".equals(uri.getPath())) {
                return new ExternalServiceHttpClient.HttpResult(
                        200,
                        "{\"names\":[\"http.server.requests\",\"unrelated.metric\"]}",
                        false,
                        4);
            }
            return new ExternalServiceHttpClient.HttpResult(
                    200,
                    "{\"name\":\"http.server.requests\",\"baseUnit\":\"seconds\","
                            + "\"measurements\":[{\"statistic\":\"COUNT\",\"value\":3}]}",
                    false,
                    6);
        });
        ExternalService service = service("http://localhost:8083", null);
        service.setMetricsPath("/actuator/metrics");

        ExternalActuatorClient.MetricsSnapshot snapshot = client.metrics(service);

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.metrics()).extracting(ExternalActuatorClient.MetricSample::name)
                .containsExactly("http.server.requests");
        assertThat(snapshot.metrics().get(0).measurements())
                .extracting(ExternalActuatorClient.Measurement::statistic)
                .containsExactly("COUNT");
    }

    private ExternalActuatorClient.HealthComponent component(
            ExternalActuatorClient.HealthSnapshot snapshot, String path) {
        return snapshot.components().stream()
                .filter(component -> path.equals(component.path()))
                .findFirst()
                .orElseThrow();
    }

    private ExternalService service(String baseUrl, String logSourceUrl) {
        ExternalService service = new ExternalService();
        service.setName("petclinic");
        service.setBaseUrl(baseUrl);
        service.setHealthPath("/actuator/health");
        service.setLogSourceUrl(logSourceUrl);
        service.setEnabled(true);
        return service;
    }

    private ExternalServiceUrlValidator validator() {
        return new ExternalServiceUrlValidator(new ExternalServiceProperties(
                List.of("localhost", "127.0.0.1"),
                List.of(8083),
                2000,
                5000,
                262144,
                "/actuator/health",
                "/actuator/metrics",
                true));
    }
}
