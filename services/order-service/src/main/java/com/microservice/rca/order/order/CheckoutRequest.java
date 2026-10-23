package com.microservice.rca.order.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CheckoutRequest(
        @NotBlank(message = "不能为空") String sku,
        @Min(value = 1, message = "必须大于 0") int quantity) {
}
