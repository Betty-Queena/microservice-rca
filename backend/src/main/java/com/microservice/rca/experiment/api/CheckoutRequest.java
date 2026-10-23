package com.microservice.rca.experiment.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 真实订单结算请求参数，用于触发订单服务到库存服务的跨服务调用。
 */
public record CheckoutRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 64) String sku,
        @Min(value = 1, message = "必须大于 0")
        @Max(value = 1000, message = "不能大于 1000") int quantity) {
}
