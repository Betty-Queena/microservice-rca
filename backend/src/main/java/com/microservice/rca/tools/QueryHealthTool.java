package com.microservice.rca.tools;

import com.microservice.rca.external.client.ExternalActuatorClient;
import com.microservice.rca.external.domain.ExternalService;
import com.microservice.rca.external.service.ExternalServiceRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * query_health：查询已注册外部 Spring Boot 服务的 Actuator 健康快照。
 *
 * <p>只用于外部服务。内置实验服务没有该工具，避免把 Actuator 健康状态
 * 当作实验服务的历史可用性证据。</p>
 */
@Component
public class QueryHealthTool implements DiagnosticTool {

    private final ExternalServiceRegistry registry;
    private final ExternalActuatorClient actuatorClient;

    public QueryHealthTool(ExternalServiceRegistry registry,
                           ExternalActuatorClient actuatorClient) {
        this.registry = registry;
        this.actuatorClient = actuatorClient;
    }

    @Override
    public String name() {
        return "query_health";
    }

    @Override
    public String description() {
        return "查询已注册外部 Spring Boot 服务的 Actuator 健康状态快照（只读），"
                + "包含整体状态和组件级状态/错误摘要";
    }

    @Override
    public ToolResult execute(ToolExecutionContext context) {
        String target = context.serviceName();
        if (target == null || target.isBlank()) {
            return ToolResult.failure("query_health 需要指定已注册的外部服务名称");
        }
        Optional<ExternalService> found = registry.findByName(target);
        if (found.isEmpty()) {
            return ToolResult.failure(
                    "query_health 仅支持已注册且启用的外部服务: " + target);
        }

        ExternalService service = found.get();
        ExternalActuatorClient.HealthSnapshot snapshot = actuatorClient.health(service);
        if (!snapshot.available()) {
            if ("UNREACHABLE".equalsIgnoreCase(snapshot.status())) {
                return ToolResult.failure("query_health 不可用: " + snapshot.message(),
                        connectivityOutput(service, snapshot));
            }
            return ToolResult.failure("query_health 不可用: " + snapshot.message());
        }

        String evidenceId = "health:" + service.getName() + ":"
                + snapshot.collectedAt().toEpochMilli();
        List<Map<String, Object>> components = new ArrayList<>();
        List<String> componentEvidenceIds = new ArrayList<>();
        Set<String> usedSuffixes = new LinkedHashSet<>();
        boolean detailsOmitted = false;
        for (ExternalActuatorClient.HealthComponent component : snapshot.components()) {
            String suffix = componentEvidenceSuffix(component.path());
            String baseSuffix = suffix;
            int duplicate = 1;
            while (!usedSuffixes.add(suffix)) {
                suffix = baseSuffix + "-" + duplicate;
                duplicate++;
            }
            String componentEvidenceId = evidenceId + ":" + suffix;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", component.path());
            item.put("status", component.status());
            if (component.errorType() != null) {
                item.put("errorType", component.errorType());
            }
            if (component.errorSummary() != null) {
                item.put("errorSummary", component.errorSummary());
            }
            if (component.detailsOmitted()) {
                item.put("detailsOmitted", true);
                detailsOmitted = true;
            }
            item.put("evidenceId", componentEvidenceId);
            components.add(item);
            componentEvidenceIds.add(componentEvidenceId);
        }

        List<String> warnings = new ArrayList<>();
        if (snapshot.componentsTruncated()) {
            warnings.add("组件数量或层级超过限制，已截断；未返回组件数约 "
                    + snapshot.omittedComponentCount());
        }
        if (detailsOmitted) {
            warnings.add("部分异常组件的错误摘要因数量限制未返回");
        }

        List<String> evidenceIds = new ArrayList<>();
        evidenceIds.add(evidenceId);
        evidenceIds.addAll(componentEvidenceIds);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("tool", name());
        output.put("external", true);
        output.put("service", service.getName());
        output.put("dataSources", List.of("external-actuator-health"));
        output.put("collectedAt", snapshot.collectedAt().toString());
        output.put("evidenceIds", evidenceIds);
        output.put("componentEvidenceIds", componentEvidenceIds);
        output.put("available", true);
        output.put("status", snapshot.status());
        output.put("httpStatus", snapshot.httpStatus());
        output.put("latencyMs", snapshot.latencyMs());
        output.put("componentCount", components.size());
        output.put("components", components);
        if (snapshot.componentsTruncated()) {
            output.put("componentsTruncated", true);
            output.put("omittedComponentCount", snapshot.omittedComponentCount());
        }
        output.put("warnings", warnings);
        output.put("dataNote",
                "健康状态与组件明细均为本次 Actuator 快照；错误摘要已脱敏，"
                        + "不代表历史可用性，也不能据此推断未返回的组件");
        return ToolResult.success(output);
    }

    private Map<String, Object> connectivityOutput(
            ExternalService service, ExternalActuatorClient.HealthSnapshot snapshot) {
        String evidenceId = "connectivity:" + service.getName() + ":health:"
                + snapshot.collectedAt().toEpochMilli();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("tool", name());
        output.put("external", true);
        output.put("service", service.getName());
        output.put("available", false);
        output.put("probe", "actuator-health");
        output.put("probeStatus", "FAILED");
        output.put("failureType", "CONNECTIVITY_FAILURE");
        output.put("connectivity", "UNREACHABLE");
        output.put("error", snapshot.message());
        output.put("collectedAt", snapshot.collectedAt().toString());
        output.put("evidenceIds", List.of(evidenceId));
        output.put("dataNote",
                "该证据仅表示本次健康探测未建立连接，不能据此断言进程已停止、"
                        + "服务已崩溃或网络配置错误；具体根因待核实");
        return output;
    }

    private String componentEvidenceSuffix(String path) {
        String safe = path == null ? "" : path.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) {
            safe = "component";
        }
        return safe.length() <= 80 ? safe : safe.substring(0, 80);
    }
}
