package com.microservice.rca.external.service;

import com.microservice.rca.agent.AgentDiagnosisService;
import com.microservice.rca.agent.api.AgentTaskDetailView;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.domain.FaultSeverity;
import com.microservice.rca.diagnosis.service.FaultEventService;
import com.microservice.rca.external.api.CreateExternalServiceRequest;
import com.microservice.rca.external.api.ExternalDiagnosisStartView;
import com.microservice.rca.external.api.ExternalServiceCheckView;
import com.microservice.rca.external.api.ExternalServiceView;
import com.microservice.rca.external.api.StartExternalDiagnosisRequest;
import com.microservice.rca.external.client.ExternalActuatorClient;
import com.microservice.rca.external.client.ExternalServiceUrlValidator;
import com.microservice.rca.external.config.ExternalServiceProperties;
import com.microservice.rca.external.domain.ExternalService;
import com.microservice.rca.external.repository.ExternalServiceRepository;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 外部服务注册、连通性检查与独立诊断入口。
 *
 * <p>外部服务第一版只读：这里没有任何重启、停止、修改配置或调用实验故障接口的方法。</p>
 */
@Service
public class ExternalServiceService {

    private final ExternalServiceRepository repository;
    private final ExternalServiceUrlValidator urlValidator;
    private final ExternalActuatorClient actuatorClient;
    private final ExternalServiceProperties properties;
    private final FaultEventService faultEventService;
    private final AgentDiagnosisService agentDiagnosisService;

    public ExternalServiceService(ExternalServiceRepository repository,
                                  ExternalServiceUrlValidator urlValidator,
                                  ExternalActuatorClient actuatorClient,
                                  ExternalServiceProperties properties,
                                  FaultEventService faultEventService,
                                  AgentDiagnosisService agentDiagnosisService) {
        this.repository = repository;
        this.urlValidator = urlValidator;
        this.actuatorClient = actuatorClient;
        this.properties = properties;
        this.faultEventService = faultEventService;
        this.agentDiagnosisService = agentDiagnosisService;
    }

    @Transactional
    public ExternalServiceView register(CreateExternalServiceRequest request) {
        String name;
        URI baseUri;
        String healthPath;
        String metricsPath;
        String logSourceUrl;
        try {
            name = urlValidator.normalizeName(request.name());
            baseUri = urlValidator.validateBaseUrl(request.baseUrl());
            healthPath = urlValidator.normalizeEndpointPath(
                    request.healthPath(), properties.defaultHealthPath());
            metricsPath = isBlank(request.metricsPath())
                    ? null
                    : urlValidator.normalizeEndpointPath(
                            request.metricsPath(), properties.defaultMetricsPath());
            logSourceUrl = urlValidator.normalizeLogSourceUrl(
                    request.logSourceUrl(), baseUri);
        } catch (IllegalArgumentException ex) {
            // 地址/端口/路径不合法属于请求错误，返回 400 而不是通用 500。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        if (repository.existsByNameIgnoreCase(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "外部服务名称已存在: " + name);
        }

        ExternalService service = new ExternalService();
        service.setName(name);
        service.setBaseUrl(baseUri.toString());
        service.setHealthPath(healthPath);
        service.setMetricsPath(metricsPath);
        service.setLogSourceUrl(logSourceUrl);
        service.setDescription(trimToNull(request.description()));
        service.setEnabled(request.enabled() == null || request.enabled());
        service.setLastCheckStatus("UNKNOWN");
        service.setLastCheckMessage("尚未执行连通性检查");
        try {
            // 立即 flush 让名称唯一索引在请求内生效，避免先查后插的并发竞争变成 500。
            return toView(repository.saveAndFlush(service));
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "外部服务名称已存在: " + name);
        }
    }

    @Transactional(readOnly = true)
    public List<ExternalServiceView> list(boolean enabledOnly) {
        List<ExternalService> services = enabledOnly
                ? repository.findAllByEnabledTrueOrderByNameAsc()
                : repository.findAllByOrderByCreatedAtDesc();
        return services.stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public ExternalServiceView detail(UUID id) {
        return toView(requireService(id));
    }

    /**
     * 主动检查健康端点并把结果写回注册记录。健康详情不落库、不返回前端。
     *
     * <p>这里刻意不包裹数据库事务：外部 HTTP 调用不应在数据库事务内执行。</p>
     */
    public ExternalServiceCheckView check(UUID id) {
        ExternalService service = requireService(id);
        ExternalActuatorClient.HealthSnapshot snapshot = actuatorClient.health(service);

        service.setLastCheckAt(snapshot.collectedAt());
        service.setLastCheckStatus(snapshot.status());
        service.setLastCheckMessage(truncate(snapshot.message(), 512));
        repository.save(service);

        return new ExternalServiceCheckView(
                service.getId(),
                service.getName(),
                snapshot.available(),
                snapshot.status(),
                snapshot.httpStatus(),
                snapshot.latencyMs(),
                truncate(snapshot.message(), 512),
                snapshot.collectedAt());
    }

    /**
     * 独立诊断入口：先创建 source=EXTERNAL 的故障事件，再复用现有 Agent 诊断任务流程。
     * 不需要任何故障注入记录或实验故障 ID，也不会创建可执行处置。
     *
     * <p>这里刻意不包裹外层事务：createExternal 必须先提交，异步 Agent 任务才能读到事件。</p>
     */
    public ExternalDiagnosisStartView startDiagnosis(UUID id,
                                                     StartExternalDiagnosisRequest request) {
        ExternalService service = requireEnabledService(id);
        StartExternalDiagnosisRequest safeRequest = request != null
                ? request : new StartExternalDiagnosisRequest(null, null, null, null);

        String eventType = isBlank(safeRequest.eventType())
                ? "EXTERNAL_ANOMALY" : safeRequest.eventType().trim();
        FaultSeverity severity = safeRequest.severity() != null
                ? safeRequest.severity() : FaultSeverity.WARNING;
        String summary = isBlank(safeRequest.summary())
                ? "外部服务 " + service.getName() + " 运行状态需要诊断"
                : safeRequest.summary().trim();

        FaultEvent event = faultEventService.createExternal(
                service.getId(),
                service.getName(),
                eventType,
                severity,
                summary,
                trimToNull(safeRequest.requestId()));

        AgentTaskDetailView task = agentDiagnosisService.startForEvent(event.getId());
        return new ExternalDiagnosisStartView(
                service.getId(),
                service.getName(),
                event.getId(),
                task.id(),
                task.status(),
                task.goal(),
                task.createdAt());
    }

    private ExternalService requireService(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "外部服务不存在: " + id));
    }

    private ExternalService requireEnabledService(UUID id) {
        ExternalService service = requireService(id);
        if (!service.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "外部服务已停用，不能发起诊断: " + service.getName());
        }
        return service;
    }

    private ExternalServiceView toView(ExternalService service) {
        return new ExternalServiceView(
                service.getId(),
                service.getName(),
                service.getBaseUrl(),
                service.getHealthPath(),
                service.getMetricsPath(),
                service.getLogSourceUrl(),
                service.getDescription(),
                service.isEnabled(),
                service.getLastCheckAt(),
                service.getLastCheckStatus(),
                service.getLastCheckMessage(),
                service.getCreatedAt(),
                service.getUpdatedAt());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
