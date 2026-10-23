package com.microservice.rca.inventory.inventory;

import com.microservice.rca.inventory.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryStore inventoryStore;

    public InventoryController(InventoryStore inventoryStore) {
        this.inventoryStore = inventoryStore;
    }

    @GetMapping
    public ApiResponse<List<InventoryItem>> list() {
        return ApiResponse.ok(inventoryStore.findAll());
    }

    @GetMapping("/{sku}")
    public ApiResponse<InventoryItem> get(@PathVariable String sku) {
        InventoryItem item = inventoryStore.find(sku)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在: " + sku));
        return ApiResponse.ok(item);
    }

    @PostMapping("/{sku}/reserve")
    public ApiResponse<ReservationResponse> reserve(@PathVariable String sku,
                                                    @Valid @RequestBody ReserveRequest request) {
        InventoryStore.Reservation reservation = inventoryStore.reserve(sku, request.quantity(), request.orderId());
        return ApiResponse.ok(new ReservationResponse(
                reservation.sku(),
                reservation.reservedQuantity(),
                reservation.remaining(),
                request.orderId()));
    }
}
