package com.microservice.rca.inventory.common;

/**
 * 库存不足属于可预期的业务冲突，由全局异常处理器映射为 HTTP 409。
 */
public class InsufficientStockException extends RuntimeException {

    private final String sku;
    private final int available;
    private final int requested;

    public InsufficientStockException(String sku, int available, int requested) {
        super("库存不足: " + sku + " 可用 " + available + "，请求 " + requested);
        this.sku = sku;
        this.available = available;
        this.requested = requested;
    }

    public String getSku() {
        return sku;
    }

    public int getAvailable() {
        return available;
    }

    public int getRequested() {
        return requested;
    }
}
