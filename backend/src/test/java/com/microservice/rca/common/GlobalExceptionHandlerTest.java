package com.microservice.rca.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.microservice.rca.diagnosis.domain.FaultEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsOptimisticLockConflictTo409() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleOptimisticLock(
                new ObjectOptimisticLockingFailureException(
                        FaultEvent.class, UUID.randomUUID()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(409);
        assertThat(response.getBody().message()).contains("已被其他操作修改");
    }
}
