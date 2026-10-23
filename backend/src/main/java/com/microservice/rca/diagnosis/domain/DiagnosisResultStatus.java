package com.microservice.rca.diagnosis.domain;

/**
 * 诊断结果状态，与任务执行状态（PENDING/RUNNING/SUCCEEDED/FAILED）分离。
 * 任务可以执行成功但结论为证据不足。
 */
public enum DiagnosisResultStatus {
    ROOT_CAUSE_IDENTIFIED,
    INSUFFICIENT_EVIDENCE
}
