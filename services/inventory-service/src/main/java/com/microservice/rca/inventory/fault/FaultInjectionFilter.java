package com.microservice.rca.inventory.fault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.rca.inventory.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 将当前激活的故障施加到库存业务接口（/api/inventory/**）。
 * 不作用于健康检查、观测接口和实验控制接口。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FaultInjectionFilter extends OncePerRequestFilter {

    public static final String EXPERIMENT_FAULT_HEADER = "X-Experiment-Fault";
    public static final String EXPERIMENT_ID_HEADER = "X-Experiment-Id";

    private final FaultInjectionService faultInjectionService;
    private final ObjectMapper objectMapper;

    public FaultInjectionFilter(FaultInjectionService faultInjectionService, ObjectMapper objectMapper) {
        this.faultInjectionService = faultInjectionService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.equals("/api/inventory") && !path.startsWith("/api/inventory/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        FaultInjectionState state = faultInjectionService.activeState();
        if (state == null) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setHeader(EXPERIMENT_ID_HEADER, state.experimentId());
        switch (state.faultType()) {
            case DELAY -> {
                response.setHeader(EXPERIMENT_FAULT_HEADER, "DELAY:" + state.delayMs() + "ms");
                try {
                    Thread.sleep(state.delayMs());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "故障注入延迟被中断");
                    return;
                }
                filterChain.doFilter(request, response);
            }
            case HTTP_500 -> {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader(EXPERIMENT_FAULT_HEADER, "HTTP_500");
                objectMapper.writeValue(response.getWriter(),
                        ApiResponse.error(500, "实验故障注入：库存服务内部错误（HTTP 500）"));
            }
            default -> filterChain.doFilter(request, response);
        }
    }
}
