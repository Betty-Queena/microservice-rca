package com.microservice.rca.order.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 将请求日志写入独立的 REQUEST_LOG JSONL 滚动文件。
 * 滚动策略见 logback-spring.xml。
 */
@Component
public class RequestLogWriter {

    private static final Logger REQUEST_LOG = LoggerFactory.getLogger("REQUEST_LOG");
    private static final Logger log = LoggerFactory.getLogger(RequestLogWriter.class);

    private final ObjectMapper objectMapper;

    public RequestLogWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(RequestLogEntry entry) {
        try {
            REQUEST_LOG.info(objectMapper.writeValueAsString(entry));
        } catch (JsonProcessingException ex) {
            log.warn("无法序列化请求日志: {}", ex.getMessage());
        }
    }
}
