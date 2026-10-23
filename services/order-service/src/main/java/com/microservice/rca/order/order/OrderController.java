package com.microservice.rca.order.order;

import com.microservice.rca.order.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/checkout")
    public ApiResponse<CheckoutResult> checkout(@Valid @RequestBody CheckoutRequest request) {
        return ApiResponse.ok(orderService.checkout(request));
    }
}
