package com.microservice.rca.order.common;

import com.microservice.rca.order.exception.InventoryRejectedException;
import com.microservice.rca.order.exception.InventoryRequestRejectedException;
import com.microservice.rca.order.exception.InventoryUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("请求参数不合法");
        return ResponseEntity.badRequest().body(ApiResponse.error(400, message));
    }

    @ExceptionHandler(InventoryRejectedException.class)
    public ResponseEntity<ApiResponse<Void>> handleInventoryRejected(InventoryRejectedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(409, ex.getMessage()));
    }

    @ExceptionHandler(InventoryRequestRejectedException.class)
    public ResponseEntity<ApiResponse<Void>> handleInventoryRequestRejected(InventoryRequestRejectedException ex) {
        log.info("Inventory request rejected [HTTP {}]: {}", ex.getStatus(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.valueOf(ex.getStatus()))
                .header("X-Downstream-Error-Type", "HTTP_4XX")
                .body(ApiResponse.error(ex.getStatus(), ex.getMessage()));
    }

    @ExceptionHandler(InventoryUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleInventoryUnavailable(InventoryUnavailableException ex) {
        log.warn("Inventory service unavailable [{}]: {}", ex.getErrorType(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .header("X-Downstream-Error-Type", ex.getErrorType())
                .body(ApiResponse.error(502, ex.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(ApiResponse.error(ex.getStatusCode().value(), ex.getReason()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "服务内部错误"));
    }
}
