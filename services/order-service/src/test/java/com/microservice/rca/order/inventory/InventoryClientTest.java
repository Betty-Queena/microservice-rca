package com.microservice.rca.order.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservice.rca.order.exception.InventoryRejectedException;
import com.microservice.rca.order.exception.InventoryUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class InventoryClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void returnsReservationOnSuccess() throws Exception {
        server = start(200, successBody(), 0);
        InventoryClient client = client(1000);

        InventoryReservation reservation = client.reserve("SKU-1001", 1, "ORD-1");

        assertThat(reservation.sku()).isEqualTo("SKU-1001");
        assertThat(reservation.remaining()).isEqualTo(99);
        assertThat(reservation.orderId()).isEqualTo("ORD-1");
    }

    @Test
    void mapsReadTimeoutToTimeoutErrorType() throws Exception {
        server = start(200, successBody(), 500);
        InventoryClient client = client(100);

        assertThatThrownBy(() -> client.reserve("SKU-1001", 1, "ORD-1"))
                .isInstanceOf(InventoryUnavailableException.class)
                .satisfies(ex -> assertThat(((InventoryUnavailableException) ex).getErrorType())
                        .isEqualTo("TIMEOUT"));
    }

    @Test
    void mapsDownstream500ToHttp5xxErrorType() throws Exception {
        server = start(500, "{\"code\":500,\"message\":\"boom\",\"data\":null}", 0);
        InventoryClient client = client(1000);

        assertThatThrownBy(() -> client.reserve("SKU-1001", 1, "ORD-1"))
                .isInstanceOf(InventoryUnavailableException.class)
                .satisfies(ex -> assertThat(((InventoryUnavailableException) ex).getErrorType())
                        .isEqualTo("HTTP_5XX"));
    }

    @Test
    void maps409ToBusinessRejection() throws Exception {
        server = start(409, "{\"code\":409,\"message\":\"库存不足: SKU-1001\",\"data\":null}", 0);
        InventoryClient client = client(1000);

        assertThatThrownBy(() -> client.reserve("SKU-1001", 99, "ORD-1"))
                .isInstanceOf(InventoryRejectedException.class)
                .hasMessageContaining("库存不足");
    }

    private InventoryClient client(int readTimeoutMs) {
        return new InventoryClient(
                RestClient.builder(),
                new ObjectMapper().findAndRegisterModules(),
                "http://localhost:" + server.getAddress().getPort(),
                1000,
                readTimeoutMs);
    }

    private HttpServer start(int status, String body, long delayMs) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/api/inventory/SKU-1001/reserve", exchange -> {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            respond(exchange, status, body);
        });
        httpServer.start();
        return httpServer;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private String successBody() {
        return "{\"code\":200,\"message\":\"OK\",\"data\":{\"sku\":\"SKU-1001\","
                + "\"reservedQuantity\":1,\"remaining\":99,\"orderId\":\"ORD-1\"},"
                + "\"timestamp\":\"2026-10-20T17:00:00Z\"}";
    }
}
