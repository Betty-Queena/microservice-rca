package com.microservice.rca.order.exception;

public class InventoryRejectedException extends RuntimeException {

    public InventoryRejectedException(String message) {
        super(message);
    }
}
