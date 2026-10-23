package com.microservice.rca.diagnosis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tool_invocation")
public class ToolInvocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "diagnosis_task_id", nullable = false)
    private UUID diagnosisTaskId;

    @Column(name = "tool_name", nullable = false, length = 128)
    private String toolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_payload", columnDefinition = "jsonb")
    private String inputPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_payload", columnDefinition = "jsonb")
    private String outputPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ToolInvocationStatus status;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "invoked_at", nullable = false, updatable = false)
    private Instant invokedAt;

    @Column(name = "sequence_no", nullable = false)
    private Integer sequenceNo;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDiagnosisTaskId() {
        return diagnosisTaskId;
    }

    public void setDiagnosisTaskId(UUID diagnosisTaskId) {
        this.diagnosisTaskId = diagnosisTaskId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getInputPayload() {
        return inputPayload;
    }

    public void setInputPayload(String inputPayload) {
        this.inputPayload = inputPayload;
    }

    public String getOutputPayload() {
        return outputPayload;
    }

    public void setOutputPayload(String outputPayload) {
        this.outputPayload = outputPayload;
    }

    public ToolInvocationStatus getStatus() {
        return status;
    }

    public void setStatus(ToolInvocationStatus status) {
        this.status = status;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getInvokedAt() {
        return invokedAt;
    }

    public void setInvokedAt(Instant invokedAt) {
        this.invokedAt = invokedAt;
    }

    public Integer getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(Integer sequenceNo) {
        this.sequenceNo = sequenceNo;
    }
}
