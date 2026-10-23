package com.microservice.rca.inventory.inventory;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record ReserveRequest(
        @Min(value = 1, message = "必须大于 0") int quantity,
        @Size(max = 64, message = "不能超过 64 个字符") String orderId) {
}
