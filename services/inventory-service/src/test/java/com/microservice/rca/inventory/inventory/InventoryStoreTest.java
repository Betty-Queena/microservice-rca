package com.microservice.rca.inventory.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microservice.rca.inventory.common.InsufficientStockException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class InventoryStoreTest {

    @Test
    void normalReservationDecrementsStockOnce() {
        InventoryStore store = new InventoryStore();

        InventoryStore.Reservation reservation = store.reserve("SKU-1001", 3, "ORD-NORMAL");

        assertThat(reservation.sku()).isEqualTo("SKU-1001");
        assertThat(reservation.reservedQuantity()).isEqualTo(3);
        assertThat(reservation.remaining()).isEqualTo(47);
        assertThat(store.find("SKU-1001").orElseThrow().available()).isEqualTo(47);
    }

    @Test
    void duplicateOrderIdReturnsOriginalResultWithoutSecondDecrement() {
        InventoryStore store = new InventoryStore();

        InventoryStore.Reservation first = store.reserve("SKU-1001", 2, "ORD-DUPLICATE");
        InventoryStore.Reservation second = store.reserve("SKU-1001", 2, "ORD-DUPLICATE");

        assertThat(first.remaining()).isEqualTo(48);
        assertThat(second).isEqualTo(first);
        assertThat(store.find("SKU-1001").orElseThrow().available()).isEqualTo(48);
    }

    @Test
    void sameOrderIdWithDifferentPayloadIsRejectedWithoutDecrement() {
        InventoryStore store = new InventoryStore();
        store.reserve("SKU-1001", 1, "ORD-CONFLICT");

        assertThatThrownBy(() -> store.reserve("SKU-1002", 1, "ORD-CONFLICT"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> store.reserve("SKU-1001", 2, "ORD-CONFLICT"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(store.find("SKU-1001").orElseThrow().available()).isEqualTo(49);
        assertThat(store.find("SKU-1002").orElseThrow().available()).isEqualTo(20);
    }

    @Test
    void differentOrderIdsAreNotMerged() {
        InventoryStore store = new InventoryStore();

        InventoryStore.Reservation first = store.reserve("SKU-1001", 1, "ORD-A");
        InventoryStore.Reservation second = store.reserve("SKU-1001", 1, "ORD-B");

        assertThat(first.remaining()).isEqualTo(49);
        assertThat(second.remaining()).isEqualTo(48);
        assertThat(store.find("SKU-1001").orElseThrow().available()).isEqualTo(48);
    }

    @Test
    void concurrentReservationsWithSameOrderIdOnlyDecrementOnce() throws Exception {
        InventoryStore store = new InventoryStore();

        List<String> outcomes = runConcurrently(16,
                () -> store.reserve("SKU-1001", 1, "ORD-CONCURRENT-SAME") != null ? "OK" : "FAIL");

        assertThat(outcomes).containsOnly("OK");
        assertThat(store.find("SKU-1001").orElseThrow().available()).isEqualTo(49);
    }

    @Test
    void concurrentReservationsWithDifferentOrderIdsDoNotOversell() throws Exception {
        InventoryStore store = new InventoryStore();
        AtomicInteger index = new AtomicInteger();

        List<String> outcomes = runConcurrently(30, () -> {
            int current = index.getAndIncrement();
            try {
                store.reserve("SKU-1002", 1, "ORD-CONCURRENT-" + current);
                return "OK";
            } catch (InsufficientStockException ex) {
                return "INSUFFICIENT_STOCK";
            }
        });

        assertThat(outcomes).filteredOn("OK"::equals).hasSize(20);
        assertThat(outcomes).filteredOn("INSUFFICIENT_STOCK"::equals).hasSize(10);
        assertThat(store.find("SKU-1002").orElseThrow().available()).isZero();
    }

    private List<String> runConcurrently(int taskCount, Callable<String> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < taskCount; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get(10, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }
}
