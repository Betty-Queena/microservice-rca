package com.microservice.rca.order.observability;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 有界内存请求日志存储。只保留最近 N 条记录，避免内存无限增长；
 * 同时由 REQUEST_LOG 滚动文件记录完整 JSONL 日志。
 */
@Component
public class RequestLogStore {

    private final int maxEntries;
    private final Deque<RequestLogEntry> entries = new ArrayDeque<>();

    public RequestLogStore(@Value("${observability.log.max-entries:5000}") int maxEntries) {
        this.maxEntries = Math.max(100, maxEntries);
    }

    public synchronized void add(RequestLogEntry entry) {
        entries.addLast(entry);
        while (entries.size() > maxEntries) {
            entries.removeFirst();
        }
    }

    public synchronized List<RequestLogEntry> query(Instant start, Instant end, String requestId, int limit) {
        return entries.stream()
                .filter(entry -> !entry.timestamp().isBefore(start) && !entry.timestamp().isAfter(end))
                .filter(entry -> requestId == null || requestId.equals(entry.requestId()))
                .sorted(Comparator.comparing(RequestLogEntry::timestamp).reversed())
                .limit(limit)
                .sorted(Comparator.comparing(RequestLogEntry::timestamp))
                .toList();
    }

    public synchronized int size() {
        return entries.size();
    }

    public int maxEntries() {
        return maxEntries;
    }
}
