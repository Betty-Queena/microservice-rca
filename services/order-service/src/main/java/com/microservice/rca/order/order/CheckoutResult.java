package com.microservice.rca.order.order;

import java.time.Instant;

public record CheckoutResult(
        String orderId,
        String status,
        String sku,
        int quantity,
        int remainingStock,
        long downstreamLatencyMs,
        Instant createdAt) {
}
