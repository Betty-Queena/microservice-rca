package com.microservice.rca.order.inventory;

public record InventoryReservation(String sku, int reservedQuantity, int remaining, String orderId) {
}
