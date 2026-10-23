package com.microservice.rca.inventory.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.microservice.rca.inventory.fault.FaultInjectionFilter;
import com.microservice.rca.inventory.observability.RequestObservationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = InventoryController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {FaultInjectionFilter.class, RequestObservationFilter.class}))
@Import(InventoryStore.class)
class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsInventoryList() throws Exception {
        mockMvc.perform(get("/api/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].sku").exists());
    }

    @Test
    void reservesInventoryForAnOrder() throws Exception {
        mockMvc.perform(post("/api/inventory/SKU-1002/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2,\"orderId\":\"ORD-TEST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sku").value("SKU-1002"))
                .andExpect(jsonPath("$.data.reservedQuantity").value(2))
                .andExpect(jsonPath("$.data.orderId").value("ORD-TEST"));
    }

    @Test
    void duplicateReservationWithSameOrderIdDoesNotDecrementTwice() throws Exception {
        String body = "{\"quantity\":2,\"orderId\":\"ORD-IDEMPOTENT\"}";

        mockMvc.perform(post("/api/inventory/SKU-1001/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remaining").value(48));
        mockMvc.perform(post("/api/inventory/SKU-1001/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remaining").value(48));

        mockMvc.perform(get("/api/inventory/SKU-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(48));
    }

    @Test
    void rejectsOrderIdLongerThanSixtyFourCharacters() throws Exception {
        String orderId = "O".repeat(65);

        mockMvc.perform(post("/api/inventory/SKU-1001/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1,\"orderId\":\"" + orderId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void rejectsInsufficientStock() throws Exception {
        mockMvc.perform(post("/api/inventory/SKU-1003/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }
}
