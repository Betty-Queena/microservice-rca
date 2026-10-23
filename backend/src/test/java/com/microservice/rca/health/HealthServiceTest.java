package com.microservice.rca.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class HealthServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private HealthService healthService;

    @BeforeEach
    void setUp() {
        healthService = new HealthService(jdbcTemplate, "rca-backend", "0.1.0");
    }

    @Test
    void reportsUpWhenDatabaseIsReachable() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        HealthData data = healthService.check();

        assertEquals("UP", data.status());
        assertEquals("UP", data.database().status());
    }

    @Test
    void reportsDegradedWhenDatabaseFails() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        HealthData data = healthService.check();

        assertEquals("DEGRADED", data.status());
        assertEquals("DOWN", data.database().status());
    }
}
