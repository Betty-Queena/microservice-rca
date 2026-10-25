"""聚合统计与 JSON / CSV / Markdown 报告生成。"""

from __future__ import annotations

import csv
import json
import math
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

from .models import CaseSuite, ScoreResult
from .scoring import (
    INVALID_STATUSES,
    STATUS_CANCELLED,
    STATUS_FAILED,
    STATUS_FALSE_ALARM,
    STATUS_HARNESS_TIMEOUT,
    STATUS_INSUFFICIENT_EVIDENCE,
    STATUS_INVALID_ENV,
    STATUS_INVALID_SAMPLE,
    STATUS_NORMAL_PASS,
    STATUS_OBSERVATION_MISMATCH,
    STATUS_ROOT_CAUSE_IDENTIFIED,
    STATUS_TASK_NOT_TERMINAL,
    STATUS_UNKNOWN_CASE,
)

EVALUATOR_VERSION = "0.1.0"

CSV_COLUMNS = [
    "suiteId",
    "labelVersion",
    "caseId",
    "scenario",
    "sampleId",
    "repeat",
    "sampleStatus",
    "diagnosisOutcome",
    "eligible",
    "observationPassed",
    "serviceCorrect",
    "categoryCorrect",
    "evidenceCoverage",
    "strictCorrect",
    "failureCategory",
    "riskFlags",
    "missingFields",
    "confidenceLevel",
    "expectedService",
    "expectedCategory",
    "actualOutcome",
    "actualHttpStatus",
    "actualDurationMs",
    "modelCallCount",
    "toolExecutionCount",
    "toolFailureCount",
    "promptTokens",
    "completionTokens",
    "totalTokens",
    "modelLatencyMs",
    "toolLatencyMs",
    "wallClockMs",
    "executionMs",
    "eventId",
    "taskId",
    "failureReason",
    "sourcePath",
]


def _count_status(results: Sequence[ScoreResult], status: str) -> int:
    return sum(1 for result in results if result.sample_status == status)


def _ratio(numerator: int, denominator: int) -> Optional[float]:
    if denominator <= 0:
        return None
    return round(numerator / denominator, 4)


def _mean(values: Sequence[float]) -> Optional[float]:
    if not values:
        return None
    return round(sum(values) / len(values), 2)


def _median(values: Sequence[float]) -> Optional[float]:
    if not values:
        return None
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2 == 1:
        value = ordered[middle]
    else:
        value = (ordered[middle - 1] + ordered[middle]) / 2.0
    return round(value, 2)


def _p95(values: Sequence[float]) -> Optional[float]:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * 0.95) - 1)
    return round(ordered[index], 2)


def _latency_stats(results: Sequence[ScoreResult], attribute: str) -> Dict[str, Any]:
    values = [
        float(value) for value in
        (getattr(result.metrics, attribute) for result in results)
        if value is not None
    ]
    return {
        "count": len(values),
        "meanMs": _mean(values),
        "medianMs": _median(values),
        "p95Ms": _p95(values),
    }


def _counter(values: Iterable[str]) -> Dict[str, int]:
    counter: Dict[str, int] = {}
    for value in values:
        if not value:
            continue
        counter[value] = counter.get(value, 0) + 1
    return {key: counter[key] for key in sorted(counter)}


def summary_stats(results: Sequence[ScoreResult]) -> Dict[str, Any]:
    normal_results = [result for result in results if result.scenario == "NORMAL"]
    diagnosis_results = [result for result in results if result.is_diagnosis]
    eligible = [result for result in results if result.eligible]
    identified = [
        result for result in eligible
        if result.sample_status == STATUS_ROOT_CAUSE_IDENTIFIED
    ]

    normal_pass = _count_status(normal_results, STATUS_NORMAL_PASS)
    false_alarm = _count_status(normal_results, STATUS_FALSE_ALARM)
    invalid_count = sum(
        1 for result in results if result.sample_status in INVALID_STATUSES)
    observation_mismatch = _count_status(results, STATUS_OBSERVATION_MISMATCH)
    root_cause_count = _count_status(results, STATUS_ROOT_CAUSE_IDENTIFIED)
    insufficient_count = _count_status(results, STATUS_INSUFFICIENT_EVIDENCE)
    failed_count = _count_status(results, STATUS_FAILED)
    harness_timeout_count = _count_status(results, STATUS_HARNESS_TIMEOUT)
    task_not_terminal_count = _count_status(results, STATUS_TASK_NOT_TERMINAL)
    cancelled_count = _count_status(results, STATUS_CANCELLED)
    unknown_case_count = _count_status(results, STATUS_UNKNOWN_CASE)
    invalid_env_count = _count_status(results, STATUS_INVALID_ENV)
    invalid_sample_count = _count_status(results, STATUS_INVALID_SAMPLE)

    token_results = [
        result for result in results
        if result.metrics.total_tokens is not None
    ]
    samples_without_usage = sum(
        1 for result in results
        if result.is_diagnosis and result.metrics.usage_available is False)
    samples_with_missing_fields = sum(1 for result in results if result.missing_fields)
    samples_with_incomplete_metrics = sum(
        1 for result in results
        if result.scenario != "NORMAL" and result.metrics.incomplete_reasons)

    return {
        "totalSamples": len(results),
        "normalSamples": len(normal_results),
        "diagnosisSamples": len(diagnosis_results),
        "eligibleDiagnosisSamples": len(eligible),
        "normalPass": normal_pass,
        "falseAlarms": false_alarm,
        "normalPassRate": _ratio(normal_pass, len(normal_results)),
        "falseAlarmRate": _ratio(false_alarm, len(normal_results)),
        "observationMismatches": observation_mismatch,
        "invalidSamples": invalid_count,
        "invalidEnvSamples": invalid_env_count,
        "invalidSampleInputs": invalid_sample_count,
        "unknownCases": unknown_case_count,
        "rootCauseIdentified": root_cause_count,
        "insufficientEvidence": insufficient_count,
        "failed": failed_count,
        "harnessTimeouts": harness_timeout_count,
        "taskNotTerminal": task_not_terminal_count,
        "cancelled": cancelled_count,
        "serviceAccuracy": _ratio(
            sum(1 for result in eligible if result.service_correct), len(eligible)),
        "categoryAccuracy": _ratio(
            sum(1 for result in eligible if result.category_correct), len(eligible)),
        "strictAccuracy": _ratio(
            sum(1 for result in eligible if result.strict_correct), len(eligible)),
        "serviceAccuracyAmongIdentified": _ratio(
            sum(1 for result in identified if result.service_correct), len(identified)),
        "categoryAccuracyAmongIdentified": _ratio(
            sum(1 for result in identified if result.category_correct), len(identified)),
        "strictAccuracyAmongIdentified": _ratio(
            sum(1 for result in identified if result.strict_correct), len(identified)),
        "evidenceCoverageAvg": _mean(
            [result.evidence_coverage for result in eligible]),
        "runMetricTotals": {
            "modelCalls": _sum_metric(eligible, "model_call_count"),
            "toolExecutions": _sum_metric(eligible, "tool_execution_count"),
            "toolFailures": _sum_metric(eligible, "tool_failure_count"),
            "promptTokens": _sum_metric(eligible, "prompt_tokens"),
            "completionTokens": _sum_metric(eligible, "completion_tokens"),
            "totalTokens": _sum_metric(eligible, "total_tokens"),
        },
        "tokenTotals": {
            "samplesWithTokens": len(token_results),
            "samplesWithoutUsage": samples_without_usage,
            "promptTokens": _sum_metric(results, "prompt_tokens"),
            "completionTokens": _sum_metric(results, "completion_tokens"),
            "totalTokens": _sum_metric(results, "total_tokens"),
        },
        "latency": {
            "wallClockMs": _latency_stats(results, "wall_clock_ms"),
            "executionMs": _latency_stats(results, "execution_ms"),
        },
        "confidenceDistribution": _counter(
            str(result.confidence_level).upper()
            for result in results if result.confidence_level),
        "failureBreakdown": _counter(
            result.failure_category for result in results if result.failure_category),
        "riskFlagCounts": _counter(
            flag for result in results for flag in result.risk_flags),
        "missingFieldCounts": _counter(
            field for result in results for field in result.missing_fields),
        "dataQuality": {
            "samplesWithMissingFields": samples_with_missing_fields,
            "samplesWithIncompleteMetrics": samples_with_incomplete_metrics,
        },
    }


def _sum_metric(results: Sequence[ScoreResult], attribute: str) -> Optional[int]:
    values = [
        getattr(result.metrics, attribute) for result in results
        if getattr(result.metrics, attribute) is not None
    ]
    if not values:
        return None
    return int(sum(values))


def build_report(suite: CaseSuite, results: Sequence[ScoreResult],
                 manifest_extra: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    generated_at = datetime.now(timezone.utc).isoformat(
        timespec="seconds").replace("+00:00", "Z")
    overall = summary_stats(results)

    per_case: List[Dict[str, Any]] = []
    known_case_ids = set()
    for case in suite.cases:
        known_case_ids.add(case.case_id)
        subset = [result for result in results if result.case_id == case.case_id]
        stats = summary_stats(subset)
        stats.update({
            "caseId": case.case_id,
            "scenario": case.scenario,
            "diagnosis": case.diagnosis,
            "notes": case.notes,
            "expected": {
                "faultObserved": case.expected.fault_observed,
                "outcome": case.expected.outcome,
                "httpStatus": case.expected.http_status,
                "minDurationMs": case.expected.min_duration_ms,
                "eventExpected": case.expected.event_expected,
                "rootCauseService": case.expected.root_cause_service,
                "faultCategory": case.expected.fault_category,
            },
        })
        per_case.append(stats)

    unknown_results = [
        result for result in results if result.case_id not in known_case_ids]
    unknown_summary = summary_stats(unknown_results) if unknown_results else None

    failures: List[Dict[str, Any]] = []
    review_candidates: List[Dict[str, Any]] = []
    for result in results:
        failure_like = result.sample_status not in (
            STATUS_NORMAL_PASS, STATUS_ROOT_CAUSE_IDENTIFIED)
        wrong_root_cause = (
            result.sample_status == STATUS_ROOT_CAUSE_IDENTIFIED
            and not result.strict_correct)
        item = {
            "sampleId": result.sample_id,
            "caseId": result.case_id,
            "scenario": result.scenario,
            "sampleStatus": result.sample_status,
            "diagnosisOutcome": result.diagnosis_outcome,
            "strictCorrect": result.strict_correct,
            "failureCategory": result.failure_category,
            "failureReason": result.failure_reason,
            "riskFlags": list(result.risk_flags),
            "missingFields": list(result.missing_fields),
            "notes": list(result.notes),
            "rootCause": result.root_cause,
            "sourcePath": result.source_path,
        }
        if failure_like or wrong_root_cause:
            failures.append(item)
        # NORMAL 场景不调用 Agent，其 Agent 指标缺失不属于需要复核的数据问题。
        metric_quality_issue = bool(
            result.metrics.incomplete_reasons) and result.scenario != "NORMAL"
        if result.risk_flags or result.missing_fields or metric_quality_issue:
            review_candidates.append(item)

    manifest = {
        "generatedAt": generated_at,
        "evaluatorVersion": EVALUATOR_VERSION,
        "mode": "offline-rescore",
        "networkCalls": False,
        "modelCalls": False,
        "suiteId": suite.suite_id,
        "labelVersion": suite.label_version,
        "labelSource": suite.label_source,
        "suitePath": suite.source_path,
        "caseCount": len(suite.cases),
        "sampleCount": len(results),
        "dataNote": (
            "本报告由离线判分生成，只对传入的原始结果做规则匹配；"
            "未调用模型，也不代表新的真实实验。"
        ),
    }
    if manifest_extra:
        manifest.update(manifest_extra)

    return {
        "manifest": manifest,
        "summary": overall,
        "cases": per_case,
        "unknownCases": unknown_summary,
        "failures": failures,
        "reviewCandidates": review_candidates,
    }


def _csv_value(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, float):
        return "%.4f" % value
    if isinstance(value, (list, tuple)):
        return ";".join(str(item) for item in value)
    return str(value)


def _csv_rows(report: Dict[str, Any], results: Sequence[ScoreResult]) -> List[Dict[str, str]]:
    suite_id = report["manifest"]["suiteId"]
    rows: List[Dict[str, str]] = []
    for result in results:
        metrics = result.metrics
        row = {
            "suiteId": suite_id,
            "labelVersion": result.label_version,
            "caseId": result.case_id,
            "scenario": result.scenario,
            "sampleId": result.sample_id,
            "repeat": result.repeat,
            "sampleStatus": result.sample_status,
            "diagnosisOutcome": result.diagnosis_outcome,
            "eligible": result.eligible,
            "observationPassed": result.observation_passed,
            "serviceCorrect": result.service_correct,
            "categoryCorrect": result.category_correct,
            "evidenceCoverage": result.evidence_coverage,
            "strictCorrect": result.strict_correct,
            "failureCategory": result.failure_category,
            "riskFlags": result.risk_flags,
            "missingFields": result.missing_fields,
            "confidenceLevel": result.confidence_level,
            "expectedService": result.expected_service,
            "expectedCategory": result.expected_category,
            "actualOutcome": result.actual_outcome,
            "actualHttpStatus": result.actual_http_status,
            "actualDurationMs": result.actual_duration_ms,
            "modelCallCount": metrics.model_call_count,
            "toolExecutionCount": metrics.tool_execution_count,
            "toolFailureCount": metrics.tool_failure_count,
            "promptTokens": metrics.prompt_tokens,
            "completionTokens": metrics.completion_tokens,
            "totalTokens": metrics.total_tokens,
            "modelLatencyMs": metrics.model_latency_ms,
            "toolLatencyMs": metrics.tool_latency_ms,
            "wallClockMs": metrics.wall_clock_ms,
            "executionMs": metrics.execution_ms,
            "eventId": result.event_id,
            "taskId": result.task_id,
            "failureReason": result.failure_reason,
            "sourcePath": result.source_path,
        }
        rows.append({key: _csv_value(row.get(key)) for key in CSV_COLUMNS})
    return rows


def _write_failures_markdown(path: Path, report: Dict[str, Any]) -> None:
    lines = [
        "# Day 7 离线判分失败与复核清单",
        "",
        "本文件由离线判分生成，只反映传入原始结果的规则匹配情况，不是新的真实实验结论。",
        "",
        "## 失败样本",
        "",
    ]
    failures = report.get("failures") or []
    if not failures:
        lines.append("无。")
    else:
        lines.append("| sampleId | caseId | status | failureCategory | riskFlags | missingFields |")
        lines.append("| --- | --- | --- | --- | --- | --- |")
        for item in failures:
            lines.append("| %s | %s | %s | %s | %s | %s |" % (
                item.get("sampleId") or "",
                item.get("caseId") or "",
                item.get("sampleStatus") or "",
                item.get("failureCategory") or "",
                ";".join(item.get("riskFlags") or []),
                ";".join(item.get("missingFields") or []),
            ))
    lines.extend(["", "## 人工复核候选", ""])
    candidates = report.get("reviewCandidates") or []
    if not candidates:
        lines.append("无。")
    else:
        for item in candidates:
            lines.append("- `%s` / `%s`: status=%s, risks=%s, missing=%s" % (
                item.get("sampleId") or "",
                item.get("caseId") or "",
                item.get("sampleStatus") or "",
                ";".join(item.get("riskFlags") or []) or "-",
                ";".join(item.get("missingFields") or []) or "-",
            ))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_reports(output_dir: Any, suite: CaseSuite, results: Sequence[ScoreResult],
                  manifest_extra: Optional[Dict[str, Any]] = None) -> Dict[str, str]:
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)
    report = build_report(suite, results, manifest_extra)

    summary_json = output_path / "summary.json"
    with summary_json.open("w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    manifest_json = output_path / "manifest.json"
    with manifest_json.open("w", encoding="utf-8") as handle:
        json.dump(report["manifest"], handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    samples_jsonl = output_path / "samples.jsonl"
    with samples_jsonl.open("w", encoding="utf-8") as handle:
        for result in results:
            handle.write(json.dumps(result.to_dict(), ensure_ascii=False) + "\n")

    summary_csv = output_path / "summary.csv"
    with summary_csv.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_COLUMNS)
        writer.writeheader()
        writer.writerows(_csv_rows(report, results))

    failures_md = output_path / "failures.md"
    _write_failures_markdown(failures_md, report)

    return {
        "summaryJson": str(summary_json),
        "manifestJson": str(manifest_json),
        "samplesJsonl": str(samples_jsonl),
        "summaryCsv": str(summary_csv),
        "failuresMarkdown": str(failures_md),
    }
