package com.microservice.rca.inventory.fault;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 实验接口访问限制：必须来自配置允许的本地地址；启用故障还要求实验开关打开。
 */
@Component
public class ExperimentAccessGuard {

    private final FaultInjectionProperties properties;

    public ExperimentAccessGuard(FaultInjectionProperties properties) {
        this.properties = properties;
    }

    public void ensureAllowed(HttpServletRequest request, boolean requireEnabled) {
        if (requireEnabled && !properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "故障注入默认禁用，请设置 EXPERIMENT_FAULT_INJECTION_ENABLED=true 后重启库存服务");
        }
        String remoteAddress = request.getRemoteAddr();
        if (!properties.getAllowedRemoteAddresses().contains(remoteAddress)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "故障注入接口仅允许本地实验环境访问，当前来源: " + remoteAddress);
        }
    }
}
