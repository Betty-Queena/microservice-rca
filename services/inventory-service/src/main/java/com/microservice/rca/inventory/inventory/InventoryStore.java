package com.microservice.rca.inventory.inventory;

import com.microservice.rca.inventory.common.InsufficientStockException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 实验用库存存储。使用内存数据，仅用于构造真实的跨服务调用链路，
 * 不代表生产库存系统，也不会伪装成真实监控数据。
 *
 * <p>库存预留按 {@code orderId} 幂等：同一订单重复预留只扣减一次，
 * 同一 orderId 携带不同商品或数量时拒绝。幂等记录保存在内存中，
 * 库存服务重启后会清空，重复请求可能再次扣减。</p>
 */
@Component
public class InventoryStore {

    private final Map<String, Product> products = new LinkedHashMap<>();
    private final Map<String, ReservationRecord> reservations = new HashMap<>();

    public InventoryStore() {
        register("SKU-1001", "实验商品 A", 50);
        register("SKU-1002", "实验商品 B", 20);
        register("SKU-1003", "实验商品 C", 0);
    }

    private void register(String sku, String name, int available) {
        products.put(sku, new Product(sku, name, available));
    }

    public synchronized List<InventoryItem> findAll() {
        return products.values().stream().map(Product::toItem).toList();
    }

    public synchronized Optional<InventoryItem> find(String sku) {
        return Optional.ofNullable(products.get(sku)).map(Product::toItem);
    }

    public Reservation reserve(String sku, int quantity) {
        return reserve(sku, quantity, null);
    }

    public synchronized Reservation reserve(String sku, int quantity, String orderId) {
        String idempotencyKey = orderId == null || orderId.isBlank() ? null : orderId.trim();
        if (idempotencyKey != null) {
            ReservationRecord existing = reservations.get(idempotencyKey);
            if (existing != null) {
                if (existing.sku.equals(sku) && existing.reservedQuantity == quantity) {
                    return new Reservation(existing.sku, existing.reservedQuantity, existing.remaining);
                }
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "订单 " + idempotencyKey + " 已用于其他库存预留，不能复用为不同商品或数量");
            }
        }

        Product product = products.get(sku);
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在: " + sku);
        }
        if (product.available < quantity) {
            throw new InsufficientStockException(sku, product.available, quantity);
        }
        product.available -= quantity;
        Reservation reservation = new Reservation(product.sku, quantity, product.available);
        if (idempotencyKey != null) {
            reservations.put(idempotencyKey,
                    new ReservationRecord(product.sku, quantity, product.available));
        }
        return reservation;
    }

    public record Reservation(String sku, int reservedQuantity, int remaining) {
    }

    private record ReservationRecord(String sku, int reservedQuantity, int remaining) {
    }

    private static final class Product {

        private final String sku;
        private final String name;
        private int available;

        private Product(String sku, String name, int available) {
            this.sku = sku;
            this.name = name;
            this.available = available;
        }

        private InventoryItem toItem() {
            return new InventoryItem(sku, name, available);
        }
    }
}
