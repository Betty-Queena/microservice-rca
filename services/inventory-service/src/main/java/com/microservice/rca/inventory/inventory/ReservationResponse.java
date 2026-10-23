package com.microservice.rca.inventory.inventory;

public record ReservationResponse(String sku, int reservedQuantity, int remaining, String orderId) {
}
