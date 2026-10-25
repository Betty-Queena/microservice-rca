package com.microservice.rca.remediation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 人工确认后的受控处置记录。表结构由 V5 迁移创建，字段与迁移脚本严格对应。
 * 动作结果与恢复验证结果分开保存，失败后重试必须新建记录。
 */
@Entity
@Table(name = "remediation_action")
public class RemediationAction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "fault_event_id", nullable = false)
    private UUID faultEventId;

    @Column(name = "diagnosis_task_id", nullable = false)
    private UUID diagnosisTaskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 64)
    private RemediationActionType actionType;

    @Column(name = "target_service", nullable = false, length = 128)
    private String targetService;

    @Column(name = "experiment_id", nullable = false)
    private UUID experimentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RemediationStatus status;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 1024)
    private String failureReason;

    @Column(name = "requested_by", nullable = false, length = 128)
    private String requestedBy;

    @Column(name = "operator_note", length = 512)
    private String operatorNote;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action_result", columnDefinition = "jsonb")
    private String actionResult;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verification_result", columnDefinition = "jsonb")
    private String verificationResult;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audit_trace", columnDefinition = "jsonb")
    private String auditTrace;

    @Column(name = "verification_request_id", length = 64)
    private String verificationRequestId;

    @Column(name = "verification_fault_event_id")
    private UUID verificationFaultEventId;

    @Column(name = "event_status_updated", nullable = false)
    private boolean eventStatusUpdated;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

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

    public UUID getDiagnosisTaskId() {
        return diagnosisTaskId;
    }

    public void setDiagnosisTaskId(UUID diagnosisTaskId) {
        this.diagnosisTaskId = diagnosisTaskId;
    }

    public RemediationActionType getActionType() {
        return actionType;
    }

    public void setActionType(RemediationActionType actionType) {
        this.actionType = actionType;
    }

    public String getTargetService() {
        return targetService;
    }

    public void setTargetService(String targetService) {
        this.targetService = targetService;
    }

    public UUID getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(UUID experimentId) {
        this.experimentId = experimentId;
    }

    public RemediationStatus getStatus() {
        return status;
    }

    public void setStatus(RemediationStatus status) {
        this.status = status;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public void setFailureCode(String failureCode) {
        this.failureCode = failureCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getOperatorNote() {
        return operatorNote;
    }

    public void setOperatorNote(String operatorNote) {
        this.operatorNote = operatorNote;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getActionResult() {
        return actionResult;
    }

    public void setActionResult(String actionResult) {
        this.actionResult = actionResult;
    }

    public String getVerificationResult() {
        return verificationResult;
    }

    public void setVerificationResult(String verificationResult) {
        this.verificationResult = verificationResult;
    }

    public String getAuditTrace() {
        return auditTrace;
    }

    public void setAuditTrace(String auditTrace) {
        this.auditTrace = auditTrace;
    }

    public String getVerificationRequestId() {
        return verificationRequestId;
    }

    public void setVerificationRequestId(String verificationRequestId) {
        this.verificationRequestId = verificationRequestId;
    }

    public UUID getVerificationFaultEventId() {
        return verificationFaultEventId;
    }

    public void setVerificationFaultEventId(UUID verificationFaultEventId) {
        this.verificationFaultEventId = verificationFaultEventId;
    }

    public boolean isEventStatusUpdated() {
        return eventStatusUpdated;
    }

    public void setEventStatusUpdated(boolean eventStatusUpdated) {
        this.eventStatusUpdated = eventStatusUpdated;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public long getVersion() {
        return version;
    }
}
