package com.microservice.rca.inventory.inventory;

import jakarta.validation.constraints.Min;

public record ReserveRequest(
        @Min(value = 1, message = "必须大于 0") int quantity,
        String orderId) {
}
