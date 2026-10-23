package com.microservice.rca.diagnosis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "diagnosis_task")
public class DiagnosisTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "fault_event_id", nullable = false)
    private UUID faultEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DiagnosisStatus status;

    @Column(name = "trigger_source", nullable = false, length = 64)
    private String triggerSource;

    @Column(name = "goal", nullable = false, length = 512)
    private String goal;

    @Column(name = "conclusion", length = 2048)
    private String conclusion;

    @Column(name = "root_cause", length = 1024)
    private String rootCause;

    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", length = 32)
    private DiagnosisResultStatus resultStatus;

    @Column(name = "confidence_level", length = 16)
    private String confidenceLevel;

    @Column(name = "failure_reason", length = 1024)
    private String failureReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "report", columnDefinition = "jsonb")
    private String report;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agent_input", columnDefinition = "jsonb")
    private String agentInput;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decision_trace", columnDefinition = "jsonb")
    private String decisionTrace;

    @Column(name = "model_name", length = 128)
    private String modelName;

    @Column(name = "started_at")
    private Instant startedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "completed_at")
    private Instant completedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getFaultEventId() {
        return faultEventId;
    }

    public void setFaultEventId(UUID faultEventId) {
        this.faultEventId = faultEventId;
    }

    public DiagnosisStatus getStatus() {
        return status;
    }

    public void setStatus(DiagnosisStatus status) {
        this.status = status;
    }

    public String getTriggerSource() {
        return triggerSource;
    }

    public void setTriggerSource(String triggerSource) {
        this.triggerSource = triggerSource;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public DiagnosisResultStatus getResultStatus() {
        return resultStatus;
    }

    public void setResultStatus(DiagnosisResultStatus resultStatus) {
        this.resultStatus = resultStatus;
    }

    public String getConfidenceLevel() {
        return confidenceLevel;
    }

    public void setConfidenceLevel(String confidenceLevel) {
        this.confidenceLevel = confidenceLevel;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getReport() {
        return report;
    }

    public void setReport(String report) {
        this.report = report;
    }

    public String getAgentInput() {
        return agentInput;
    }

    public void setAgentInput(String agentInput) {
        this.agentInput = agentInput;
    }

    public String getDecisionTrace() {
        return decisionTrace;
    }

    public void setDecisionTrace(String decisionTrace) {
        this.decisionTrace = decisionTrace;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
