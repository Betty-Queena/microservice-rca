package com.microservice.rca.inventory.inventory;

import com.microservice.rca.inventory.common.InsufficientStockException;
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
 */
@Component
public class InventoryStore {

    private final Map<String, Product> products = new LinkedHashMap<>();

    public InventoryStore() {
        register("SKU-1001", "实验商品 A", 50);
        register("SKU-1002", "实验商品 B", 20);
        register("SKU-1003", "实验商品 C", 0);
    }

    private void register(String sku, String name, int available) {
        products.put(sku, new Product(sku, name, available));
    }

    public List<InventoryItem> findAll() {
        return products.values().stream().map(Product::toItem).toList();
    }

    public Optional<InventoryItem> find(String sku) {
        return Optional.ofNullable(products.get(sku)).map(Product::toItem);
    }

    public Reservation reserve(String sku, int quantity) {
        Product product = products.get(sku);
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在: " + sku);
        }
        synchronized (product) {
            if (product.available < quantity) {
                throw new InsufficientStockException(sku, product.available, quantity);
            }
            product.available -= quantity;
            return new Reservation(product.sku, quantity, product.available);
        }
    }

    public record Reservation(String sku, int reservedQuantity, int remaining) {
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
