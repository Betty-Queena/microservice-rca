package com.microservice.rca.order.exception;

/**
 * 下游库存服务明确拒绝了请求（400/404/422 等业务错误）。
 * 与 {@link InventoryUnavailableException} 区分：这里保留下游状态码，
 * 避免把不存在的 SKU、非法参数误报为订单服务 502。
 */
public class InventoryRequestRejectedException extends RuntimeException {

    private final int status;

    public InventoryRequestRejectedException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
