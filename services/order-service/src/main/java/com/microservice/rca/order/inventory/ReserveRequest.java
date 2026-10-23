package com.microservice.rca.order.inventory;

public record ReserveRequest(int quantity, String orderId) {
}
