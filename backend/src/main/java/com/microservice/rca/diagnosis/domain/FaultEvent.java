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
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "fault_event")
public class FaultEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "service_name", nullable = false, length = 128)
    private String serviceName;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private FaultSeverity severity;

    @Column(name = "summary", nullable = false, length = 512)
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private FaultEventStatus status;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "experiment_id")
    private UUID experimentId;

    @Column(name = "external_service_id")
    private UUID externalServiceId;

    @Column(name = "experiment_fault_type", length = 64)
    private String experimentFaultType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "experiment_parameters", columnDefinition = "jsonb")
    private String experimentParameters;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "observed_result", columnDefinition = "jsonb")
    private String observedResult;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "source", nullable = false, length = 32)
    private String source = "MANUAL";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", columnDefinition = "jsonb")
    private String evidence;

    @Column(name = "evidence_collected_at")
    private Instant evidenceCollectedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public FaultSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(FaultSeverity severity) {
        this.severity = severity;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public FaultEventStatus getStatus() {
        return status;
    }

    public void setStatus(FaultEventStatus status) {
        this.status = status;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
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

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public UUID getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(UUID experimentId) {
        this.experimentId = experimentId;
    }

    public UUID getExternalServiceId() {
        return externalServiceId;
    }

    public void setExternalServiceId(UUID externalServiceId) {
        this.externalServiceId = externalServiceId;
    }

    public String getExperimentFaultType() {
        return experimentFaultType;
    }

    public void setExperimentFaultType(String experimentFaultType) {
        this.experimentFaultType = experimentFaultType;
    }

    public String getExperimentParameters() {
        return experimentParameters;
    }

    public void setExperimentParameters(String experimentParameters) {
        this.experimentParameters = experimentParameters;
    }

    public String getObservedResult() {
        return observedResult;
    }

    public void setObservedResult(String observedResult) {
        this.observedResult = observedResult;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public Instant getEvidenceCollectedAt() {
        return evidenceCollectedAt;
    }

    public void setEvidenceCollectedAt(Instant evidenceCollectedAt) {
        this.evidenceCollectedAt = evidenceCollectedAt;
    }
}
