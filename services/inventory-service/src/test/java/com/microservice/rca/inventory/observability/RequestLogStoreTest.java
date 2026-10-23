package com.microservice.rca.inventory.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RequestLogStoreTest {

    private static final Instant BASE = Instant.parse("2026-10-20T17:00:00Z");

    @Test
    void queryFiltersByTimeWindowAndRequestId() {
        RequestLogStore store = new RequestLogStore(5000);
        store.add(entry("log-1", BASE.plusSeconds(10), "req-1"));
        store.add(entry("log-2", BASE.plusSeconds(20), "req-2"));
        store.add(entry("log-3", BASE.plusSeconds(30), "req-1"));

        assertThat(store.query(BASE, BASE.plusSeconds(60), "req-1", 50))
                .extracting(RequestLogEntry::id)
                .containsExactly("log-1", "log-3");
        assertThat(store.query(BASE.plusSeconds(15), BASE.plusSeconds(25), null, 50))
                .extracting(RequestLogEntry::id)
                .containsExactly("log-2");
    }

    @Test
    void queryAppliesLimitButKeepsAscendingOrder() {
        RequestLogStore store = new RequestLogStore(5000);
        for (int i = 0; i < 10; i++) {
            store.add(entry("log-" + i, BASE.plusSeconds(i), null));
        }

        assertThat(store.query(BASE, BASE.plusSeconds(60), null, 3))
                .extracting(RequestLogEntry::id)
                .containsExactly("log-7", "log-8", "log-9");
    }

    @Test
    void storeIsBoundedByConfiguredCapacity() {
        RequestLogStore store = new RequestLogStore(100);
        for (int i = 0; i < 150; i++) {
            store.add(entry("log-" + i, BASE.plusSeconds(i), null));
        }

        assertThat(store.size()).isEqualTo(100);
        assertThat(store.maxEntries()).isEqualTo(100);
        assertThat(store.query(BASE, BASE.plusSeconds(1000), null, 200))
                .extracting(RequestLogEntry::id)
                .doesNotContain("log-0")
                .contains("log-149");
    }

    private RequestLogEntry entry(String id, Instant timestamp, String requestId) {
        return new RequestLogEntry(id, timestamp, "inventory-service", requestId,
                "POST", "/api/inventory/SKU-1001/reserve", 200, 10, "NONE");
    }
}
