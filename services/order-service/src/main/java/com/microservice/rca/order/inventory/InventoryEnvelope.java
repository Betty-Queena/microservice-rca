package com.microservice.rca.order.inventory;

import java.time.Instant;

public record InventoryEnvelope(int code, String message, InventoryReservation data, Instant timestamp) {
}
