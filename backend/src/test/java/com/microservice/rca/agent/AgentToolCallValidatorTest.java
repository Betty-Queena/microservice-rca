package com.microservice.rca.agent;

import static com.microservice.rca.agent.AgentTestSupport.input;
import static com.microservice.rca.agent.AgentTestSupport.logsOutput;
import static com.microservice.rca.agent.AgentTestSupport.metricsOutput;
import static com.microservice.rca.agent.AgentTestSupport.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microservice.rca.agent.AgentToolCallValidator.ValidatedCall;
import com.microservice.rca.tools.DiagnosticToolRegistry;
import com.microservice.rca.tools.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentToolCallValidatorTest {

    private final AgentToolCallValidator validator = new AgentToolCallValidator();
    private final DiagnosticToolRegistry registry = AgentTestSupport.registry(
            tool("query_logs", ToolResult.success(logsOutput())),
            tool("query_metrics", ToolResult.success(metricsOutput())));

    @Test
    void acceptsQueryLogsWithWhitelistedArguments() {
        ValidatedCall call = validator.validate(input(registry, 4, 3), "query_logs",
                Map.of("service", "order-service", "requestId", "exp-abc123",
                        "limit", 20, "lookbackMinutes", 10));

        assertThat(call.toolName()).isEqualTo("query_logs");
        assertThat(call.service()).isEqualTo("order-service");
        assertThat(call.requestId()).isEqualTo("exp-abc123");
        assertThat(call.limit()).isEqualTo(20);
        assertThat(call.lookbackMinutes()).isEqualTo(10);
        assertThat(call.windowStart()).isBefore(call.windowEnd());
    }

    @Test
    void rejectsUnregisteredTool() {
        assertThatThrownBy(() -> validator.validate(input(registry, 4, 3), "run_shell",
                Map.of("command", "rm -rf /")))
                .isInstanceOf(AgentValidationException.class)
                .hasMessageContaining("未注册的工具");
    }

    @Test
    void rejectsToolThatIsRegisteredForHumansButNotAgentSafe() {
        DiagnosticToolRegistry extended = AgentTestSupport.registry(
                tool("query_logs", ToolResult.success(logsOutput())),
                tool("experiment_trigger", ToolResult.success(Map.of())));
        assertThatThrownBy(() -> validator.validate(input(extended, 4, 3), "experiment_trigger", Map.of()))
                .isInstanceOf(AgentValidationException.class)
                .hasMessageContaining("不支持 Agent 调用");
    }

    @Test
    void rejectsUnknownArguments() {
        assertThatThrownBy(() -> validator.validate(input(registry, 4, 3), "query_logs",
                Map.of("sql", "select * from users")))
                .isInstanceOf(AgentValidationException.class)
                .hasMessageContaining("不接受参数: sql");
    }

    @Test
    void rejectsUnknownService() {
        assertThatThrownBy(() -> validator.validate(input(registry, 4, 3), "query_metrics",
                Map.of("service", "user-service")))
                .isInstanceOf(AgentValidationException.class)
                .hasMessageContaining("不支持的服务");
    }

    @Test
    void rejectsLookbackOutOfRange() {
        assertThatThrownBy(() -> validator.validate(input(registry, 4, 3), "query_metrics",
                Map.of("lookbackMinutes", 600)))
                .isInstanceOf(AgentValidationException.class)
                .hasMessageContaining("超出范围");
    }

    @Test
    void rejectsNonIntegerArguments() {
        assertThatThrownBy(() -> validator.validate(input(registry, 4, 3), "query_logs",
                Map.of("limit", "100")))
                .isInstanceOf(AgentValidationException.class)
                .hasMessageContaining("必须是整数");
    }

    @Test
    void rejectsInvalidRequestId() {
        assertThatThrownBy(() -> validator.validate(input(registry, 4, 3), "query_logs",
                Map.of("requestId", "abc; drop table users")))
                .isInstanceOf(AgentValidationException.class)
                .hasMessageContaining("requestId 格式不合法");
    }

    @Test
    void rejectsRequestIdForMetrics() {
        assertThatThrownBy(() -> validator.validate(input(registry, 4, 3), "query_metrics",
                Map.of("requestId", "exp-abc123")))
                .isInstanceOf(AgentValidationException.class)
                .hasMessageContaining("不接受参数");
    }
}
