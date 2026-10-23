package com.microservice.rca.inventory.health;

import com.microservice.rca.inventory.common.ApiResponse;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final String serviceName;
    private final String version;

    public HealthController(@Value("${spring.application.name}") String serviceName,
                            @Value("${app.version:0.1.0}") String version) {
        this.serviceName = serviceName;
        this.version = version;
    }

    @GetMapping("/health")
    public ApiResponse<HealthData> health() {
        return ApiResponse.ok(new HealthData(serviceName, version, "UP", Instant.now()));
    }
}
