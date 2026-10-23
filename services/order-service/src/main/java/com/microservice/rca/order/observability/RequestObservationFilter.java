package com.microservice.rca.order.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 业务请求观测过滤器：生成/透传 requestId，记录真实状态码、耗时和错误类型，
 * 并写入有界内存日志与分钟级指标。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestObservationFilter extends OncePerRequestFilter {

    static final String DOWNSTREAM_ERROR_HEADER = "X-Downstream-Error-Type";

    private final RequestLogStore logStore;
    private final RequestMetricsStore metricsStore;
    private final RequestLogWriter logWriter;
    private final String serviceName;
    private final String pathPrefix;

    public RequestObservationFilter(RequestLogStore logStore,
                                    RequestMetricsStore metricsStore,
                                    RequestLogWriter logWriter,
                                    @Value("${spring.application.name}") String serviceName,
                                    @Value("${observability.business-path-prefix:/api/orders}") String pathPrefix) {
        this.logStore = logStore;
        this.metricsStore = metricsStore;
        this.logWriter = logWriter;
        this.serviceName = serviceName;
        this.pathPrefix = pathPrefix;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.equals(pathPrefix) && !path.startsWith(pathPrefix + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put(RequestIdContext.MDC_KEY, requestId);
        request.setAttribute(RequestIdContext.ATTRIBUTE, requestId);
        response.setHeader(RequestIdContext.HEADER, requestId);

        Instant timestamp = Instant.now();
        long startedAt = System.nanoTime();
        String failureType = null;
        try {
            filterChain.doFilter(request, response);
        } catch (Exception ex) {
            failureType = ex.getClass().getSimpleName();
            throw ex;
        } finally {
            long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            int status = response.getStatus();
            if (failureType != null && status < 400) {
                status = 500;
            }
            String errorType = classify(status, failureType, response.getHeader(DOWNSTREAM_ERROR_HEADER));
            RequestLogEntry entry = new RequestLogEntry(
                    UUID.randomUUID().toString(),
                    timestamp,
                    serviceName,
                    requestId,
                    request.getMethod(),
                    request.getRequestURI(),
                    status,
                    durationMs,
                    errorType);
            logStore.add(entry);
            metricsStore.record(timestamp, durationMs, status);
            logWriter.write(entry);
            MDC.remove(RequestIdContext.MDC_KEY);
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String incoming = RequestIdContext.sanitize(request.getHeader(RequestIdContext.HEADER));
        if (incoming != null) {
            return incoming;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String classify(int status, String failureType, String downstreamErrorType) {
        if (failureType != null) {
            return "UNHANDLED_EXCEPTION";
        }
        if (downstreamErrorType != null && !downstreamErrorType.isBlank()) {
            return "DOWNSTREAM_" + downstreamErrorType;
        }
        if (status >= 500) {
            return "HTTP_5XX";
        }
        if (status == 409) {
            return "BUSINESS_CONFLICT";
        }
        if (status >= 400) {
            return "HTTP_4XX";
        }
        return "NONE";
    }
}
