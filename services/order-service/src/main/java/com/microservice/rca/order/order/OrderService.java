package com.microservice.rca.order.order;

import com.microservice.rca.order.inventory.InventoryClient;
import com.microservice.rca.order.inventory.InventoryReservation;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final InventoryClient inventoryClient;

    public OrderService(InventoryClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    public CheckoutResult checkout(CheckoutRequest request) {
        String orderId = "ORD-" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase(Locale.ROOT);

        long start = System.nanoTime();
        InventoryReservation reservation = inventoryClient.reserve(request.sku(), request.quantity(), orderId);
        long latencyMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        return new CheckoutResult(
                orderId,
                "CONFIRMED",
                request.sku(),
                request.quantity(),
                reservation.remaining(),
                latencyMs,
                Instant.now());
    }
}
