package com.microservice.rca.order.order;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.microservice.rca.order.exception.InventoryUnavailableException;
import com.microservice.rca.order.inventory.InventoryClient;
import com.microservice.rca.order.inventory.InventoryReservation;
import com.microservice.rca.order.observability.RequestObservationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = OrderController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = RequestObservationFilter.class))
@Import(OrderService.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InventoryClient inventoryClient;

    @Test
    void createsOrderThroughInventoryService() throws Exception {
        when(inventoryClient.reserve(eq("SKU-1001"), eq(2), anyString()))
                .thenReturn(new InventoryReservation("SKU-1001", 2, 48, "ORD-TEST"));

        mockMvc.perform(post("/api/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-1001\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.sku").value("SKU-1001"))
                .andExpect(jsonPath("$.data.remainingStock").value(48));
    }

    @Test
    void reportsBadGatewayWhenInventoryIsUnavailable() throws Exception {
        when(inventoryClient.reserve(anyString(), eq(1), anyString()))
                .thenThrow(new InventoryUnavailableException("库存服务不可达"));

        mockMvc.perform(post("/api/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-1001\",\"quantity\":1}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502));
    }

    @Test
    void rejectsInvalidQuantity() throws Exception {
        mockMvc.perform(post("/api/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-1001\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
