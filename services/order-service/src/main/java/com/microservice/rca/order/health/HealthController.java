package com.microservice.rca.order.health;

import com.microservice.rca.order.common.ApiResponse;
import com.microservice.rca.order.inventory.InventoryClient;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final InventoryClient inventoryClient;
    private final String serviceName;
    private final String version;

    public HealthController(InventoryClient inventoryClient,
                            @Value("${spring.application.name}") String serviceName,
                            @Value("${app.version:0.1.0}") String version) {
        this.inventoryClient = inventoryClient;
        this.serviceName = serviceName;
        this.version = version;
    }

    @GetMapping("/health")
    public ApiResponse<HealthData> health() {
        boolean inventoryUp = inventoryClient.ping();
        HealthData.DependencyHealth dependency = new HealthData.DependencyHealth(
                "inventory-service",
                inventoryUp ? "UP" : "DOWN",
                inventoryClient.getBaseUrl());

        return ApiResponse.ok(new HealthData(
                serviceName,
                version,
                inventoryUp ? "UP" : "DEGRADED",
                Instant.now(),
                dependency));
    }
}
