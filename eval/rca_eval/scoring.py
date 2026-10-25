"""确定性离线判分。

判分只使用预定义 ground truth 与原始结果，不调用模型、不做语义推断。
所有关键词命中都做简单的否定词检查；否定检查不是自然语言理解，
可能产生误判，因此同时保留 ``riskFlags`` 供人工复核。
"""

from __future__ import annotations

import re
from datetime import datetime, timezone
from fnmatch import fnmatchcase
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

from .models import (
    KNOWN_SERVICE_ALIASES,
    KNOWN_SERVICES,
    REQUIRED_REPORT_FIELDS,
    CaseSuite,
    Diagnosis,
    EvalCase,
    Metrics,
    RawSample,
    SCENARIO_NORMAL,
    ScoreResult,
)

STATUS_NORMAL_PASS = "NORMAL_PASS"
STATUS_FALSE_ALARM = "FALSE_ALARM"
STATUS_ROOT_CAUSE_IDENTIFIED = "ROOT_CAUSE_IDENTIFIED"
STATUS_INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE"
STATUS_FAILED = "FAILED"
STATUS_HARNESS_TIMEOUT = "HARNESS_TIMEOUT"
STATUS_OBSERVATION_MISMATCH = "OBSERVATION_MISMATCH"
STATUS_INVALID_ENV = "INVALID_ENV"
STATUS_INVALID_SAMPLE = "INVALID_SAMPLE"
STATUS_UNKNOWN_CASE = "UNKNOWN_CASE"
STATUS_TASK_NOT_TERMINAL = "TASK_NOT_TERMINAL"
STATUS_CANCELLED = "CANCELLED"

INVALID_STATUSES = {
    STATUS_INVALID_ENV,
    STATUS_INVALID_SAMPLE,
    STATUS_UNKNOWN_CASE,
    STATUS_TASK_NOT_TERMINAL,
    STATUS_CANCELLED,
}

EVIDENCE_ID_PATTERN = re.compile(r"^(log|metric):[^:]+:.+$")
NEGATION_MARKERS = ("不", "未", "没有", "并非", "不是", "无", "not", "no", "never")
# 否定词只在当前分句内生效，避免“并非 A，而是 B”把 B 也误判为否定。
NEGATION_CLAUSE_BOUNDARIES = (
    "，", ",", "。", ".", "；", ";", "！", "!", "？", "?", "\n",
    "而是", "但是", "但", "however", "but",
)

TERMINAL_TASK_STATUSES = {"SUCCEEDED", "FAILED", "CANCELLED"}


def _clause_start(text: str, index: int) -> int:
    start = 0
    lowered = text.lower()
    for boundary in NEGATION_CLAUSE_BOUNDARIES:
        position = lowered.rfind(boundary, 0, index)
        if position >= 0:
            start = max(start, position + len(boundary))
    return start


def _is_negated(text: str, index: int) -> bool:
    start = max(_clause_start(text, index), index - 10)
    window = text[start:index].lower()
    return any(marker in window for marker in NEGATION_MARKERS)


def _iter_keyword_hits(text: Optional[str], keywords: Sequence[str]) -> Iterable[Tuple[str, int, bool]]:
    if not text:
        return
    lowered = text.lower()
    for keyword in keywords:
        needle = keyword.strip()
        if not needle:
            continue
        target = needle.lower()
        start = 0
        while True:
            index = lowered.find(target, start)
            if index < 0:
                break
            if target.isdigit():
                before = text[index - 1] if index > 0 else ""
                after_index = index + len(target)
                after = text[after_index] if after_index < len(text) else ""
                if before.isdigit() or after.isdigit():
                    start = index + 1
                    continue
            yield keyword, index, _is_negated(text, index)
            start = index + max(1, len(target))


def _has_non_negated(text: Optional[str], keywords: Sequence[str]) -> bool:
    return any(not negated for _, _, negated in _iter_keyword_hits(text, keywords))


def _first_non_negated_other_service(
        text: Optional[str], expected_service: Optional[str]) -> Optional[str]:
    """返回首个被非否定提及的非预期服务。"""
    for other_service in KNOWN_SERVICES:
        if other_service == expected_service:
            continue
        aliases = KNOWN_SERVICE_ALIASES.get(other_service, (other_service,))
        if _has_non_negated(text, aliases):
            return other_service
    return None


def _parse_instant(value: Optional[str]) -> Optional[datetime]:
    if not value or not isinstance(value, str):
        return None
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def _diff_ms(start: Optional[str], end: Optional[str]) -> Optional[int]:
    start_dt = _parse_instant(start)
    end_dt = _parse_instant(end)
    if start_dt is None or end_dt is None:
        return None
    delta = (end_dt - start_dt).total_seconds()
    if delta < 0:
        return None
    return int(round(delta * 1000))


def _int_value(value: Any) -> Optional[int]:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float) and value.is_integer():
        return int(value)
    if isinstance(value, str):
        try:
            return int(value.strip())
        except ValueError:
            return None
    return None


def _sum_optional(values: Iterable[Optional[int]]) -> Optional[int]:
    present = [value for value in values if value is not None]
    if not present:
        return None
    return sum(present)


def _check_observation(case: EvalCase, sample: RawSample,
                       missing: List[str]) -> bool:
    observation = sample.observation
    if not observation.present:
        missing.append("observation")
        return False

    passed = True
    if observation.fault_observed is None:
        missing.append("observation.faultObserved")
        passed = False
    elif observation.fault_observed != case.expected.fault_observed:
        passed = False

    if not observation.outcome:
        missing.append("observation.outcome")
        passed = False
    elif observation.outcome.upper() != case.expected.outcome.upper():
        passed = False

    if case.expected.http_status is not None:
        if observation.http_status is None:
            missing.append("observation.httpStatus")
            passed = False
        elif observation.http_status != case.expected.http_status:
            passed = False

    if case.expected.min_duration_ms is not None:
        if observation.duration_ms is None:
            missing.append("observation.durationMs")
            passed = False
        elif observation.duration_ms < case.expected.min_duration_ms:
            passed = False

    return passed


def _classify_failure(reason: Optional[str]) -> str:
    if not reason:
        return "UNKNOWN"
    lowered = reason.lower()
    if "deepseek_api_key" in lowered or "api key" in lowered or "未配置" in reason:
        return "CONFIG_MISSING"
    if any(token in lowered for token in ("超时", "timeout", "预算", "deadline", "budget")):
        return "BUDGET_TIMEOUT"
    if "线程池" in reason or "队列" in reason or "rejected" in lowered:
        return "EXECUTOR_REJECTED"
    if "模型" in reason or "deepseek" in lowered or "api" in lowered:
        return "MODEL_ERROR"
    if "异常" in reason or "exception" in lowered:
        return "AGENT_ERROR"
    return "UNKNOWN"


def _extract_metrics(diagnosis: Optional[Diagnosis]) -> Metrics:
    if diagnosis is None or not diagnosis.present:
        return Metrics(incomplete_reasons=("诊断结果缺失，无法统计运行指标",))

    trace = list(diagnosis.decision_trace)
    summary = None
    for entry in reversed(trace):
        if str(entry.get("type", "")).upper() == "AGENT_RUN_SUMMARY":
            summary = entry
            break

    reasons: List[str] = []
    report = diagnosis.report or {}

    def stat_value(summary_key: str, report_key: Optional[str] = None) -> Optional[int]:
        if summary is not None:
            value = _int_value(summary.get(summary_key))
            if value is not None:
                return value
        if report_key:
            value = _int_value(report.get(report_key))
            if value is not None:
                return value
        return None

    model_calls = stat_value("modelCallCount", "modelCallCount")
    model_calls_without_usage = stat_value(
        "modelCallsWithoutUsage", "modelCallsWithoutUsage")
    prompt_tokens = stat_value("promptTokens", "promptTokens")
    completion_tokens = stat_value("completionTokens", "completionTokens")
    total_tokens = stat_value("totalTokens", "totalTokens")
    model_latency = stat_value("modelLatencyMsTotal", "modelLatencyMs")

    if model_calls is None and trace:
        model_calls = sum(
            1 for entry in trace
            if str(entry.get("type", "")).upper() in ("MODEL_DECISION", "TOOL_REJECTED")
            and entry.get("modelCallAttempted") is not False)

    if model_calls is None and not trace:
        reasons.append("缺少决策轨迹，无法统计模型调用")

    if prompt_tokens is None or completion_tokens is None:
        trace_prompt = 0
        trace_completion = 0
        trace_usage = False
        for entry in trace:
            if str(entry.get("type", "")).upper() not in ("MODEL_DECISION", "TOOL_REJECTED"):
                continue
            if entry.get("usageAvailable") is not True:
                continue
            entry_prompt = _int_value(entry.get("promptTokens"))
            entry_completion = _int_value(entry.get("completionTokens"))
            if entry_prompt is None or entry_completion is None:
                continue
            trace_prompt += entry_prompt
            trace_completion += entry_completion
            trace_usage = True
        if trace_usage:
            if prompt_tokens is None:
                prompt_tokens = trace_prompt
            if completion_tokens is None:
                completion_tokens = trace_completion

    if total_tokens is None and prompt_tokens is not None and completion_tokens is not None:
        total_tokens = prompt_tokens + completion_tokens

    if model_latency is None and trace:
        latencies = [
            _int_value(entry.get("latencyMs"))
            for entry in trace
            if str(entry.get("type", "")).upper() in ("MODEL_DECISION", "TOOL_REJECTED")
        ]
        model_latency = _sum_optional(latencies)

    tool_execution_count: Optional[int]
    if diagnosis.tool_invocations:
        tool_execution_count = len(diagnosis.tool_invocations)
    elif trace:
        tool_execution_count = sum(
            1 for entry in trace if str(entry.get("type", "")).upper() == "TOOL_EXECUTION")
    else:
        tool_execution_count = None

    tool_failure_count: Optional[int] = None
    if diagnosis.tool_invocations:
        tool_failure_count = sum(
            1 for invocation in diagnosis.tool_invocations
            if str(invocation.status or "").upper() in ("FAILED", "ERROR"))
    elif trace:
        tool_failure_count = sum(
            1 for entry in trace
            if str(entry.get("type", "")).upper() == "TOOL_EXECUTION"
            and str(entry.get("status", "")).upper() in ("FAILED", "ERROR"))

    tool_latencies = [invocation.latency_ms for invocation in diagnosis.tool_invocations]
    tool_latency = _sum_optional(tool_latencies)
    if diagnosis.tool_invocations and any(value is None for value in tool_latencies):
        reasons.append("部分工具调用缺少耗时")

    if prompt_tokens is None and completion_tokens is None and total_tokens is None:
        reasons.append("Token 统计缺失")
        usage_available: Optional[bool] = False
    elif model_calls_without_usage is not None:
        usage_available = model_calls_without_usage == 0
    else:
        usage_available = None

    wall_clock = _diff_ms(diagnosis.created_at, diagnosis.completed_at)
    execution = _diff_ms(diagnosis.started_at, diagnosis.completed_at)
    if wall_clock is None:
        reasons.append("任务墙钟耗时缺失")

    return Metrics(
        model_call_count=model_calls,
        model_calls_without_usage=model_calls_without_usage,
        tool_execution_count=tool_execution_count,
        tool_failure_count=tool_failure_count,
        prompt_tokens=prompt_tokens,
        completion_tokens=completion_tokens,
        total_tokens=total_tokens,
        model_latency_ms=model_latency,
        tool_latency_ms=tool_latency,
        wall_clock_ms=wall_clock,
        execution_ms=execution,
        usage_available=usage_available,
        incomplete_reasons=tuple(reasons),
    )


def _evidence_coverage(case: EvalCase, diagnosis: Diagnosis,
                       missing: List[str], risks: List[str]) -> Tuple[Tuple[str, ...], float]:
    values = diagnosis.report_values()
    evidence_ids = values["supportingEvidenceIds"]
    if not evidence_ids:
        missing.append("report.supportingEvidenceIds")
        risks.append("NO_SUPPORTING_EVIDENCE")
        return evidence_ids, 0.0

    for evidence_id in evidence_ids:
        if not EVIDENCE_ID_PATTERN.match(evidence_id):
            risks.append("EVIDENCE_ID_FORMAT:%s" % evidence_id)

    judge = case.judge
    if judge is None or not judge.evidence_matchers:
        missing.append("judge.evidenceMatchers")
        return evidence_ids, 0.0

    matched = set()
    for evidence_id in evidence_ids:
        for matcher in judge.evidence_matchers:
            if fnmatchcase(evidence_id, matcher):
                matched.add(matcher)

    coverage = min(1.0, len(matched) / float(max(1, judge.min_evidence_matchers)))
    if coverage < 1.0:
        risks.append("EVIDENCE_COVERAGE_LOW")
    return evidence_ids, coverage


def _score_report(case: EvalCase, diagnosis: Diagnosis,
                  missing: List[str], risks: List[str], notes: List[str]) -> Dict[str, Any]:
    values = diagnosis.report_values()

    for field_name in REQUIRED_REPORT_FIELDS:
        value = values.get(field_name)
        if field_name in ("affectedServices", "supportingEvidenceIds"):
            if not value:
                missing.append("report.%s" % field_name)
        elif not value:
            missing.append("report.%s" % field_name)

    confidence = values.get("confidenceLevel")
    if confidence and str(confidence).upper() not in ("HIGH", "MEDIUM", "LOW"):
        risks.append("INVALID_CONFIDENCE:%s" % confidence)

    root_cause = values.get("rootCause")
    hypothesis = values.get("hypothesis")
    affected_services = values.get("affectedServices") or ()
    evidence_ids, coverage = _evidence_coverage(case, diagnosis, missing, risks)

    judge = case.judge
    service_correct = False
    category_correct = False
    if judge is not None:
        root_text = " ".join(item for item in (root_cause, hypothesis) if item)
        affected_text = " ".join(affected_services)
        expected_service = case.expected.root_cause_service

        # rootCause/hypothesis 是根因判定的主依据；affectedServices 可能包含
        # 被级联影响的服务，不能覆盖根因描述中“不是某服务”的否定结论。
        root_hits = list(_iter_keyword_hits(root_text, judge.service_aliases))
        for keyword, _, negated in root_hits:
            if negated:
                risks.append("NEGATED_SERVICE_ALIAS:%s" % keyword)
        service_correct = any(not negated for _, _, negated in root_hits)
        other_service = _first_non_negated_other_service(root_text, expected_service)

        if not service_correct and other_service:
            risks.append("SERVICE_CONFLICT:%s" % other_service)
        elif not service_correct and not root_hits and not other_service:
            # 根因描述没有点名任何服务时，才用 affectedServices 兜底。
            affected_hits = list(_iter_keyword_hits(affected_text, judge.service_aliases))
            for keyword, _, negated in affected_hits:
                if negated:
                    risks.append("NEGATED_SERVICE_ALIAS:%s" % keyword)
            service_correct = any(not negated for _, _, negated in affected_hits)

        if not service_correct and not other_service:
            other_service = _first_non_negated_other_service(affected_text, expected_service)
            if other_service:
                risks.append("SERVICE_CONFLICT:%s" % other_service)

        category_text = " ".join(item for item in (root_cause, hypothesis) if item)
        category_hits = list(_iter_keyword_hits(category_text, judge.category_keywords))
        category_correct = any(not negated for _, _, negated in category_hits)
        for keyword, _, negated in category_hits:
            if negated:
                risks.append("NEGATED_CATEGORY_KEYWORD:%s" % keyword)

        for keyword, _, negated in _iter_keyword_hits(category_text, judge.risk_keywords):
            if negated:
                notes.append("风险关键词是否定表述: %s" % keyword)
            else:
                risks.append("RISK_KEYWORD:%s" % keyword)

    contradicting = values.get("contradictingEvidenceIds") or ()
    if contradicting:
        notes.append("报告包含 %d 条矛盾证据" % len(contradicting))

    strict_correct = bool(
        service_correct and category_correct and coverage >= 1.0 and not missing)

    return {
        "service_correct": service_correct,
        "category_correct": category_correct,
        "evidence_coverage": coverage,
        "strict_correct": strict_correct,
        "evidence_ids": evidence_ids,
        "contradicting_ids": contradicting,
        "root_cause": root_cause,
        "hypothesis": hypothesis,
        "confidence_level": confidence,
    }


def _base_result(case: EvalCase, sample: RawSample, label_version: Optional[str] = None) -> ScoreResult:
    observation = sample.observation
    return ScoreResult(
        sample_id=sample.sample_id or "unknown",
        case_id=case.case_id,
        repeat=sample.repeat,
        scenario=case.scenario,
        is_diagnosis=case.diagnosis,
        sample_status=STATUS_INVALID_SAMPLE,
        diagnosis_outcome=STATUS_INVALID_SAMPLE,
        eligible=False,
        observation_passed=False,
        service_correct=False,
        category_correct=False,
        evidence_coverage=0.0,
        strict_correct=False,
        expected_service=case.expected.root_cause_service,
        expected_category=case.expected.fault_category,
        expected_outcome=case.expected.outcome,
        actual_outcome=observation.outcome,
        actual_http_status=observation.http_status,
        actual_duration_ms=observation.duration_ms,
        event_id=observation.fault_event_id,
        source_path=sample.source_path,
        label_version=label_version,
    )


def score_sample(case: EvalCase, sample: RawSample,
                 label_version: Optional[str] = None) -> ScoreResult:
    result = _base_result(case, sample, label_version)
    observation = sample.observation
    missing: List[str] = []
    risks: List[str] = []
    notes: List[str] = list(sample.parse_warnings) + list(observation.parse_warnings)
    if sample.diagnosis is not None:
        notes.extend(sample.diagnosis.parse_warnings)

    observation_passed = _check_observation(case, sample, missing)
    result.observation_passed = observation_passed
    result.metrics = _extract_metrics(sample.diagnosis)

    # 运行器已经确认是环境/接口/清理问题，不能退化成普通数据缺失或观察不符。
    if sample.harness_error:
        result.sample_status = STATUS_INVALID_ENV
        result.diagnosis_outcome = STATUS_INVALID_ENV
        result.failure_category = "HARNESS_ERROR"
        result.failure_reason = sample.harness_error
        result.missing_fields = tuple(dict.fromkeys(missing))
        result.risk_flags = tuple(dict.fromkeys(risks))
        result.notes = tuple(dict.fromkeys(notes))
        return result

    if not case.diagnosis:
        # NORMAL 场景本来就不调用 Agent，Token、调用次数和诊断耗时属于不适用。
        # 不能复用“诊断缺失”的统计原因，否则会被误计为指标缺失。
        if sample.diagnosis is None or not sample.diagnosis.present:
            result.metrics = Metrics()
        event_created = bool(observation.fault_event_id) or bool(
            sample.diagnosis is not None and sample.diagnosis.present)
        if not observation.present:
            result.sample_status = STATUS_INVALID_SAMPLE
            result.diagnosis_outcome = "NOT_RUN"
        elif event_created:
            result.sample_status = STATUS_FALSE_ALARM
            result.diagnosis_outcome = "NOT_RUN"
            risks.append("FALSE_ALARM")
        elif not observation_passed:
            result.sample_status = STATUS_OBSERVATION_MISMATCH
            result.diagnosis_outcome = "NOT_RUN"
        else:
            result.sample_status = STATUS_NORMAL_PASS
            result.diagnosis_outcome = "NOT_RUN"
        result.risk_flags = tuple(dict.fromkeys(risks))
        result.missing_fields = tuple(dict.fromkeys(missing))
        result.notes = tuple(dict.fromkeys(notes))
        return result

    if not observation.present:
        result.sample_status = STATUS_INVALID_SAMPLE
        result.diagnosis_outcome = STATUS_INVALID_SAMPLE
        result.missing_fields = tuple(dict.fromkeys(missing))
        result.risk_flags = tuple(dict.fromkeys(risks))
        result.notes = tuple(dict.fromkeys(notes))
        return result

    if not observation_passed:
        result.sample_status = STATUS_OBSERVATION_MISMATCH
        result.diagnosis_outcome = STATUS_OBSERVATION_MISMATCH
        result.missing_fields = tuple(dict.fromkeys(missing))
        result.risk_flags = tuple(dict.fromkeys(risks))
        result.notes = tuple(dict.fromkeys(notes))
        return result

    if case.expected.event_expected and not observation.fault_event_id:
        missing.append("observation.faultEventId")
        risks.append("MISSING_FAULT_EVENT_ID")

    diagnosis = sample.diagnosis
    if diagnosis is None or not diagnosis.present:
        missing.append("diagnosis")
        result.sample_status = STATUS_INVALID_SAMPLE
        result.diagnosis_outcome = STATUS_INVALID_SAMPLE
        result.missing_fields = tuple(dict.fromkeys(missing))
        result.risk_flags = tuple(dict.fromkeys(risks))
        result.notes = tuple(dict.fromkeys(notes))
        return result

    result.task_id = diagnosis.task_id
    result.failure_reason = diagnosis.failure_reason
    task_status = (diagnosis.status or "").upper()
    result_status = (diagnosis.result_status or "").upper()

    if task_status == "SUCCEEDED" and result_status == "ROOT_CAUSE_IDENTIFIED":
        result.sample_status = STATUS_ROOT_CAUSE_IDENTIFIED
        result.diagnosis_outcome = STATUS_ROOT_CAUSE_IDENTIFIED
        result.eligible = True
        report_score = _score_report(case, diagnosis, missing, risks, notes)
        result.service_correct = report_score["service_correct"]
        result.category_correct = report_score["category_correct"]
        result.evidence_coverage = report_score["evidence_coverage"]
        result.strict_correct = report_score["strict_correct"]
        result.root_cause = report_score["root_cause"]
        result.hypothesis = report_score["hypothesis"]
        result.confidence_level = report_score["confidence_level"]
        result.supporting_evidence_ids = report_score["evidence_ids"]
        result.contradicting_evidence_ids = report_score["contradicting_ids"]
    elif task_status == "SUCCEEDED" and result_status == "INSUFFICIENT_EVIDENCE":
        result.sample_status = STATUS_INSUFFICIENT_EVIDENCE
        result.diagnosis_outcome = STATUS_INSUFFICIENT_EVIDENCE
        result.eligible = True
        reason = diagnosis.report.get("reason") if isinstance(diagnosis.report, dict) else None
        if not reason:
            missing.append("report.reason")
    elif task_status == "FAILED":
        result.sample_status = STATUS_FAILED
        result.diagnosis_outcome = STATUS_FAILED
        result.eligible = True
        result.failure_category = _classify_failure(diagnosis.failure_reason)
    elif task_status in ("PENDING", "RUNNING"):
        result.sample_status = STATUS_TASK_NOT_TERMINAL
        result.diagnosis_outcome = STATUS_TASK_NOT_TERMINAL
        missing.append("diagnosis.terminalStatus")
        result.failure_category = "TASK_NOT_TERMINAL"
    elif task_status in ("HARNESS_TIMEOUT", "TIMEOUT"):
        result.sample_status = STATUS_HARNESS_TIMEOUT
        result.diagnosis_outcome = STATUS_HARNESS_TIMEOUT
        result.failure_category = "HARNESS_TIMEOUT"
    elif task_status == "CANCELLED":
        result.sample_status = STATUS_CANCELLED
        result.diagnosis_outcome = STATUS_CANCELLED
        result.failure_category = "CANCELLED"
    elif task_status == "SUCCEEDED" and result_status not in ("ROOT_CAUSE_IDENTIFIED", "INSUFFICIENT_EVIDENCE"):
        if not result_status:
            missing.append("diagnosis.resultStatus")
        result.sample_status = STATUS_INVALID_SAMPLE
        result.diagnosis_outcome = STATUS_INVALID_SAMPLE
        result.failure_category = "INVALID_RESULT_STATUS"
    else:
        if not task_status:
            missing.append("diagnosis.status")
        result.sample_status = STATUS_INVALID_SAMPLE
        result.diagnosis_outcome = STATUS_INVALID_SAMPLE
        result.failure_category = "INVALID_TASK_STATUS"

    result.missing_fields = tuple(dict.fromkeys(missing))
    result.risk_flags = tuple(dict.fromkeys(risks))
    result.notes = tuple(dict.fromkeys(notes))
    return result


def score_unknown_case(sample: RawSample) -> ScoreResult:
    observation = sample.observation
    status = STATUS_UNKNOWN_CASE if sample.case_id else STATUS_INVALID_SAMPLE
    missing = ["caseId"] if not sample.case_id else []
    return ScoreResult(
        sample_id=sample.sample_id or "unknown",
        case_id=sample.case_id or "UNKNOWN",
        repeat=sample.repeat,
        scenario="UNKNOWN",
        is_diagnosis=False,
        sample_status=status,
        diagnosis_outcome=STATUS_INVALID_SAMPLE,
        eligible=False,
        observation_passed=False,
        service_correct=False,
        category_correct=False,
        evidence_coverage=0.0,
        strict_correct=False,
        failure_category="UNKNOWN_CASE" if sample.case_id else "MISSING_CASE_ID",
        risk_flags=("UNKNOWN_CASE",) if sample.case_id else ("MISSING_CASE_ID",),
        missing_fields=tuple(missing),
        notes=tuple(dict.fromkeys(
            list(sample.parse_warnings) + list(observation.parse_warnings))),
        actual_outcome=observation.outcome,
        actual_http_status=observation.http_status,
        actual_duration_ms=observation.duration_ms,
        event_id=observation.fault_event_id,
        source_path=sample.source_path,
        metrics=_extract_metrics(sample.diagnosis),
    )


def score_all(suite: CaseSuite, samples: Sequence[RawSample]) -> List[ScoreResult]:
    by_id = {case.case_id: case for case in suite.cases}
    results: List[ScoreResult] = []
    for sample in samples:
        case = by_id.get(sample.case_id) if sample.case_id else None
        if case is None:
            results.append(score_unknown_case(sample))
        else:
            results.append(score_sample(case, sample, suite.label_version))
    return results
