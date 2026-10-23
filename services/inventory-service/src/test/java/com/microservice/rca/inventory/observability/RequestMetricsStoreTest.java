package com.microservice.rca.inventory.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RequestMetricsStoreTest {

    private static final Instant BASE = Instant.parse("2026-10-20T17:00:00Z");

    @Test
    void aggregatesRealRequestsIntoMinuteBuckets() {
        RequestMetricsStore store = new RequestMetricsStore(180);
        store.record(BASE.plusSeconds(5), 100, 200);
        store.record(BASE.plusSeconds(20), 200, 500);
        store.record(BASE.plusSeconds(40), 300, 409);

        assertThat(store.query(BASE, BASE.plusSeconds(60))).hasSize(1);
        MetricBucket bucket = store.query(BASE, BASE.plusSeconds(60)).get(0);
        assertThat(bucket.minute()).isEqualTo(BASE);
        assertThat(bucket.requestCount()).isEqualTo(3);
        assertThat(bucket.serverErrorCount()).isEqualTo(1);
        assertThat(bucket.clientErrorCount()).isEqualTo(1);
        assertThat(bucket.maxDurationMs()).isEqualTo(300);
        assertThat(bucket.averageDurationMs()).isCloseTo(200.0, within(0.001));
    }

    @Test
    void separateMinutesAreSeparateBuckets() {
        RequestMetricsStore store = new RequestMetricsStore(180);
        store.record(BASE, 10, 200);
        store.record(BASE.plusSeconds(60), 20, 200);

        assertThat(store.query(BASE, BASE.plusSeconds(120)))
                .extracting(MetricBucket::minute)
                .containsExactly(BASE, BASE.plusSeconds(60));
    }

    @Test
    void queryWindowExcludesOutOfRangeBuckets() {
        RequestMetricsStore store = new RequestMetricsStore(180);
        store.record(BASE, 10, 200);
        store.record(BASE.plusSeconds(120), 20, 200);

        assertThat(store.query(BASE.plusSeconds(60), BASE.plusSeconds(180)))
                .extracting(MetricBucket::minute)
                .containsExactly(BASE.plusSeconds(120));
    }

    @Test
    void retentionCleanupDropsOldBuckets() {
        RequestMetricsStore store = new RequestMetricsStore(10);
        store.record(BASE, 10, 200);
        store.record(BASE.plusSeconds(11 * 60), 20, 200);

        assertThat(store.query(BASE, BASE.plusSeconds(20 * 60)))
                .extracting(MetricBucket::minute)
                .containsExactly(BASE.plusSeconds(11 * 60));
    }
}
