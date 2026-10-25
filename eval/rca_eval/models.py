"""Day 7 评测基础设施的数据模型。

本模块只定义数据结构与“宽容解析”逻辑，不包含任何网络调用。
原始样本解析必须容忍缺失字段，以便离线重判时能明确报告数据质量问题，
而不是直接中断整批结果。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

SCENARIO_HTTP_500 = "HTTP_500"
SCENARIO_DELAY = "DELAY"
SCENARIO_NORMAL = "NORMAL"
KNOWN_SCENARIOS = (SCENARIO_HTTP_500, SCENARIO_DELAY, SCENARIO_NORMAL)

KNOWN_SERVICES = ("order-service", "inventory-service")
KNOWN_SERVICE_ALIASES = {
    "order-service": ("order-service", "订单服务"),
    "inventory-service": ("inventory-service", "库存服务"),
}

REQUIRED_REPORT_FIELDS = (
    "rootCause",
    "hypothesis",
    "affectedServices",
    "supportingEvidenceIds",
    "confidenceLevel",
)


class CaseValidationError(ValueError):
    """case 文件或原始样本文件无法通过校验。"""

    def __init__(self, errors: List[str]):
        self.errors = list(errors)
        super().__init__("; ".join(self.errors) if self.errors else "评测输入无效")


@dataclass(frozen=True)
class Injection:
    fault_type: str
    delay_ms: Optional[int] = None


@dataclass(frozen=True)
class ExpectedResult:
    fault_observed: bool
    outcome: str
    http_status: Optional[int] = None
    min_duration_ms: Optional[int] = None
    event_expected: bool = True
    root_cause_service: Optional[str] = None
    fault_category: str = "NONE"


@dataclass(frozen=True)
class JudgeRule:
    service_aliases: Tuple[str, ...]
    category_keywords: Tuple[str, ...]
    risk_keywords: Tuple[str, ...] = ()
    evidence_matchers: Tuple[str, ...] = ()
    min_evidence_matchers: int = 1


@dataclass(frozen=True)
class Defaults:
    repeat: int = 1
    cooldown_seconds: float = 5.0
    wait_after_execution_ms: int = 2000
    fault_duration_seconds: int = 120
    sku: str = "SKU-1001"
    quantity: int = 1
    diagnosis_timeout_seconds: int = 240


@dataclass(frozen=True)
class EvalCase:
    case_id: str
    scenario: str
    injection: Optional[Injection]
    expected: ExpectedResult
    judge: Optional[JudgeRule]
    diagnosis: bool
    notes: str = ""


@dataclass(frozen=True)
class CaseSuite:
    suite_id: str
    label_version: str
    label_source: str
    defaults: Defaults
    environment_requirements: Dict[str, Any]
    cases: Tuple[EvalCase, ...]
    source_path: Optional[str] = None


def _first(data: Dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in data:
            return data[key]
    return None


def _as_optional_str(value: Any, warnings: List[str], path: str) -> Optional[str]:
    if value is None:
        return None
    if isinstance(value, str):
        return value.strip()
    warnings.append("%s 不是字符串，已忽略" % path)
    return None


def _as_optional_bool(value: Any, warnings: List[str], path: str) -> Optional[bool]:
    if value is None:
        return None
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered in ("true", "false"):
            return lowered == "true"
    warnings.append("%s 不是布尔值，已忽略" % path)
    return None


def _as_optional_int(value: Any, warnings: List[str], path: str) -> Optional[int]:
    if value is None:
        return None
    if isinstance(value, bool):
        warnings.append("%s 不是整数，已忽略" % path)
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float) and value.is_integer():
        return int(value)
    if isinstance(value, str):
        try:
            return int(value.strip())
        except ValueError:
            pass
    warnings.append("%s 不是整数，已忽略" % path)
    return None


def _as_str_list(value: Any, warnings: List[str], path: str) -> Tuple[str, ...]:
    if value is None:
        return ()
    if not isinstance(value, list):
        warnings.append("%s 不是数组，已忽略" % path)
        return ()
    result: List[str] = []
    for index, item in enumerate(value):
        if isinstance(item, str) and item.strip():
            result.append(item.strip())
        else:
            warnings.append("%s[%d] 不是非空字符串，已忽略" % (path, index))
    return tuple(result)


def _as_dict(value: Any, warnings: List[str], path: str) -> Dict[str, Any]:
    if value is None:
        return {}
    if isinstance(value, dict):
        return dict(value)
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
        except (TypeError, ValueError):
            warnings.append("%s 不是合法 JSON 对象，已忽略" % path)
            return {}
        if isinstance(parsed, dict):
            return parsed
    warnings.append("%s 不是对象，已忽略" % path)
    return {}


def _as_dict_list(value: Any, warnings: List[str], path: str) -> Tuple[Dict[str, Any], ...]:
    if value is None:
        return ()
    if not isinstance(value, list):
        warnings.append("%s 不是数组，已忽略" % path)
        return ()
    result: List[Dict[str, Any]] = []
    for index, item in enumerate(value):
        if isinstance(item, dict):
            result.append(dict(item))
        else:
            warnings.append("%s[%d] 不是对象，已忽略" % (path, index))
    return tuple(result)


@dataclass(frozen=True)
class Observation:
    present: bool = False
    fault_observed: Optional[bool] = None
    outcome: Optional[str] = None
    http_status: Optional[int] = None
    duration_ms: Optional[int] = None
    downstream_error_type: Optional[str] = None
    fault_event_id: Optional[str] = None
    parse_warnings: Tuple[str, ...] = ()

    @classmethod
    def from_dict(cls, data: Any, path: str = "observation") -> "Observation":
        warnings: List[str] = []
        if data is None:
            return cls(present=False, parse_warnings=(path + " 缺失",))
        if not isinstance(data, dict):
            return cls(present=False, parse_warnings=(path + " 不是对象",))

        return cls(
            present=True,
            fault_observed=_as_optional_bool(
                _first(data, "faultObserved", "fault_observed"), warnings, path + ".faultObserved"),
            outcome=_as_optional_str(_first(data, "outcome"), warnings, path + ".outcome"),
            http_status=_as_optional_int(
                _first(data, "httpStatus", "http_status"), warnings, path + ".httpStatus"),
            duration_ms=_as_optional_int(
                _first(data, "durationMs", "duration_ms"), warnings, path + ".durationMs"),
            downstream_error_type=_as_optional_str(
                _first(data, "downstreamErrorType", "downstream_error_type"),
                warnings, path + ".downstreamErrorType"),
            fault_event_id=_as_optional_str(
                _first(data, "faultEventId", "fault_event_id", "eventId"),
                warnings, path + ".faultEventId"),
            parse_warnings=tuple(warnings),
        )


@dataclass(frozen=True)
class ToolInvocation:
    tool_name: Optional[str] = None
    status: Optional[str] = None
    latency_ms: Optional[int] = None
    error_message: Optional[str] = None

    @classmethod
    def from_dict(cls, data: Dict[str, Any], path: str, warnings: List[str]) -> "ToolInvocation":
        return cls(
            tool_name=_as_optional_str(
                _first(data, "toolName", "tool_name", "tool"), warnings, path + ".toolName"),
            status=_as_optional_str(_first(data, "status"), warnings, path + ".status"),
            latency_ms=_as_optional_int(
                _first(data, "latencyMs", "latency_ms"), warnings, path + ".latencyMs"),
            error_message=_as_optional_str(
                _first(data, "errorMessage", "error_message", "error"), warnings, path + ".errorMessage"),
        )


@dataclass(frozen=True)
class Diagnosis:
    present: bool = False
    status: Optional[str] = None
    result_status: Optional[str] = None
    failure_reason: Optional[str] = None
    report: Dict[str, Any] = field(default_factory=dict)
    root_cause: Optional[str] = None
    hypothesis: Optional[str] = None
    affected_services: Tuple[str, ...] = ()
    supporting_evidence_ids: Tuple[str, ...] = ()
    contradicting_evidence_ids: Tuple[str, ...] = ()
    confidence_level: Optional[str] = None
    decision_trace: Tuple[Dict[str, Any], ...] = ()
    tool_invocations: Tuple[ToolInvocation, ...] = ()
    created_at: Optional[str] = None
    started_at: Optional[str] = None
    completed_at: Optional[str] = None
    task_id: Optional[str] = None
    parse_warnings: Tuple[str, ...] = ()

    @classmethod
    def from_dict(cls, data: Any, path: str = "diagnosis") -> "Diagnosis":
        warnings: List[str] = []
        if data is None:
            return cls(present=False, parse_warnings=(path + " 缺失",))
        if not isinstance(data, dict):
            return cls(present=False, parse_warnings=(path + " 不是对象",))

        report = _as_dict(_first(data, "report"), warnings, path + ".report")
        raw_invocations = _first(data, "toolInvocations", "tool_invocations", "toolCalls")
        invocation_items: List[ToolInvocation] = []
        if raw_invocations is not None:
            if isinstance(raw_invocations, list):
                for index, item in enumerate(raw_invocations):
                    if isinstance(item, dict):
                        invocation_items.append(ToolInvocation.from_dict(
                            item, "%s.toolInvocations[%d]" % (path, index), warnings))
                    else:
                        warnings.append("%s.toolInvocations[%d] 不是对象，已忽略" % (path, index))
            else:
                warnings.append("%s.toolInvocations 不是数组，已忽略" % path)

        return cls(
            present=True,
            status=_as_optional_str(_first(data, "status"), warnings, path + ".status"),
            result_status=_as_optional_str(
                _first(data, "resultStatus", "result_status"), warnings, path + ".resultStatus"),
            failure_reason=_as_optional_str(
                _first(data, "failureReason", "failure_reason"), warnings, path + ".failureReason"),
            report=report,
            root_cause=_as_optional_str(
                _first(data, "rootCause", "root_cause"), warnings, path + ".rootCause"),
            hypothesis=_as_optional_str(
                _first(data, "hypothesis"), warnings, path + ".hypothesis"),
            affected_services=_as_str_list(
                _first(data, "affectedServices", "affected_services"),
                warnings, path + ".affectedServices"),
            supporting_evidence_ids=_as_str_list(
                _first(data, "supportingEvidenceIds", "supporting_evidence_ids"),
                warnings, path + ".supportingEvidenceIds"),
            contradicting_evidence_ids=_as_str_list(
                _first(data, "contradictingEvidenceIds", "contradicting_evidence_ids"),
                warnings, path + ".contradictingEvidenceIds"),
            confidence_level=_as_optional_str(
                _first(data, "confidenceLevel", "confidence_level"),
                warnings, path + ".confidenceLevel"),
            decision_trace=_as_dict_list(
                _first(data, "decisionTrace", "decision_trace"),
                warnings, path + ".decisionTrace"),
            tool_invocations=tuple(invocation_items),
            created_at=_as_optional_str(
                _first(data, "createdAt", "created_at"), warnings, path + ".createdAt"),
            started_at=_as_optional_str(
                _first(data, "startedAt", "started_at"), warnings, path + ".startedAt"),
            completed_at=_as_optional_str(
                _first(data, "completedAt", "completed_at"), warnings, path + ".completedAt"),
            task_id=_as_optional_str(
                _first(data, "taskId", "task_id", "id"), warnings, path + ".taskId"),
            parse_warnings=tuple(warnings),
        )

    def report_values(self) -> Dict[str, Any]:
        """合并任务详情顶层字段与 report 对象，优先使用非空顶层值。"""

        def pick_str(top: Optional[str], key: str) -> Optional[str]:
            if top is not None and str(top).strip():
                return str(top).strip()
            value = self.report.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
            return None

        def pick_list(top: Tuple[str, ...], key: str) -> Tuple[str, ...]:
            if top:
                return top
            value = self.report.get(key)
            if isinstance(value, list):
                return tuple(str(item).strip() for item in value
                             if isinstance(item, str) and item.strip())
            return ()

        confidence = self.confidence_level
        if not confidence:
            value = self.report.get("confidenceLevel")
            if isinstance(value, str) and value.strip():
                confidence = value.strip()

        return {
            "rootCause": pick_str(self.root_cause, "rootCause"),
            "hypothesis": pick_str(self.hypothesis, "hypothesis"),
            "affectedServices": pick_list(self.affected_services, "affectedServices"),
            "supportingEvidenceIds": pick_list(
                self.supporting_evidence_ids, "supportingEvidenceIds"),
            "contradictingEvidenceIds": pick_list(
                self.contradicting_evidence_ids, "contradictingEvidenceIds"),
            "confidenceLevel": confidence,
        }


@dataclass(frozen=True)
class RawSample:
    sample_id: Optional[str]
    case_id: Optional[str]
    repeat: Optional[int]
    observation: Observation
    diagnosis: Optional[Diagnosis] = None
    harness_error: Optional[str] = None
    schema_version: Optional[str] = None
    started_at: Optional[str] = None
    completed_at: Optional[str] = None
    source_path: Optional[str] = None
    parse_warnings: Tuple[str, ...] = ()

    @classmethod
    def from_dict(cls, data: Any, source_path: Optional[str] = None,
                  fallback_sample_id: Optional[str] = None) -> "RawSample":
        warnings: List[str] = []
        if not isinstance(data, dict):
            return cls(
                sample_id=fallback_sample_id,
                case_id=None,
                repeat=None,
                observation=Observation(present=False, parse_warnings=("样本不是对象",)),
                schema_version=None,
                source_path=source_path,
                parse_warnings=("样本不是 JSON 对象",),
            )

        diagnosis_value = _first(data, "diagnosis", "diagnosisTask", "task")
        diagnosis = Diagnosis.from_dict(diagnosis_value) if diagnosis_value is not None else None
        if diagnosis is None and "diagnosis" in data and data.get("diagnosis") is None:
            # 正常场景显式使用 diagnosis=null，属于合法输入，不产生告警。
            warnings = list(warnings)

        sample = cls(
            sample_id=_as_optional_str(
                _first(data, "sampleId", "sample_id"), warnings, "sample.sampleId")
            or fallback_sample_id,
            case_id=_as_optional_str(
                _first(data, "caseId", "case_id"), warnings, "sample.caseId"),
            repeat=_as_optional_int(_first(data, "repeat"), warnings, "sample.repeat"),
            observation=Observation.from_dict(_first(data, "observation", "observedResult")),
            diagnosis=diagnosis,
            harness_error=_as_optional_str(
                _first(data, "harnessError", "harness_error", "error"),
                warnings, "sample.harnessError"),
            schema_version=_as_optional_str(
                _first(data, "schemaVersion", "schema_version"), warnings, "sample.schemaVersion"),
            started_at=_as_optional_str(
                _first(data, "startedAt", "started_at"), warnings, "sample.startedAt"),
            completed_at=_as_optional_str(
                _first(data, "completedAt", "completed_at"), warnings, "sample.completedAt"),
            source_path=source_path,
            parse_warnings=tuple(warnings),
        )
        return sample


@dataclass
class Metrics:
    model_call_count: Optional[int] = None
    model_calls_without_usage: Optional[int] = None
    tool_execution_count: Optional[int] = None
    tool_failure_count: Optional[int] = None
    prompt_tokens: Optional[int] = None
    completion_tokens: Optional[int] = None
    total_tokens: Optional[int] = None
    model_latency_ms: Optional[int] = None
    tool_latency_ms: Optional[int] = None
    wall_clock_ms: Optional[int] = None
    execution_ms: Optional[int] = None
    usage_available: Optional[bool] = None
    incomplete_reasons: Tuple[str, ...] = ()

    def to_dict(self) -> Dict[str, Any]:
        return {
            "modelCallCount": self.model_call_count,
            "modelCallsWithoutUsage": self.model_calls_without_usage,
            "toolExecutionCount": self.tool_execution_count,
            "toolFailureCount": self.tool_failure_count,
            "promptTokens": self.prompt_tokens,
            "completionTokens": self.completion_tokens,
            "totalTokens": self.total_tokens,
            "modelLatencyMs": self.model_latency_ms,
            "toolLatencyMs": self.tool_latency_ms,
            "wallClockMs": self.wall_clock_ms,
            "executionMs": self.execution_ms,
            "usageAvailable": self.usage_available,
            "incompleteReasons": list(self.incomplete_reasons),
        }


@dataclass
class ScoreResult:
    sample_id: str
    case_id: str
    repeat: Optional[int]
    scenario: str
    is_diagnosis: bool
    sample_status: str
    diagnosis_outcome: str
    eligible: bool
    observation_passed: bool
    service_correct: bool
    category_correct: bool
    evidence_coverage: float
    strict_correct: bool
    failure_category: Optional[str] = None
    risk_flags: Tuple[str, ...] = ()
    missing_fields: Tuple[str, ...] = ()
    notes: Tuple[str, ...] = ()
    confidence_level: Optional[str] = None
    expected_service: Optional[str] = None
    expected_category: Optional[str] = None
    expected_outcome: Optional[str] = None
    actual_outcome: Optional[str] = None
    actual_http_status: Optional[int] = None
    actual_duration_ms: Optional[int] = None
    root_cause: Optional[str] = None
    hypothesis: Optional[str] = None
    supporting_evidence_ids: Tuple[str, ...] = ()
    contradicting_evidence_ids: Tuple[str, ...] = ()
    metrics: Metrics = field(default_factory=Metrics)
    event_id: Optional[str] = None
    task_id: Optional[str] = None
    failure_reason: Optional[str] = None
    source_path: Optional[str] = None
    label_version: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "sampleId": self.sample_id,
            "caseId": self.case_id,
            "repeat": self.repeat,
            "scenario": self.scenario,
            "isDiagnosis": self.is_diagnosis,
            "labelVersion": self.label_version,
            "sampleStatus": self.sample_status,
            "diagnosisOutcome": self.diagnosis_outcome,
            "eligible": self.eligible,
            "observationPassed": self.observation_passed,
            "serviceCorrect": self.service_correct,
            "categoryCorrect": self.category_correct,
            "evidenceCoverage": round(self.evidence_coverage, 4),
            "strictCorrect": self.strict_correct,
            "failureCategory": self.failure_category,
            "riskFlags": list(self.risk_flags),
            "missingFields": list(self.missing_fields),
            "notes": list(self.notes),
            "confidenceLevel": self.confidence_level,
            "expectedService": self.expected_service,
            "expectedCategory": self.expected_category,
            "expectedOutcome": self.expected_outcome,
            "actualOutcome": self.actual_outcome,
            "actualHttpStatus": self.actual_http_status,
            "actualDurationMs": self.actual_duration_ms,
            "rootCause": self.root_cause,
            "hypothesis": self.hypothesis,
            "supportingEvidenceIds": list(self.supporting_evidence_ids),
            "contradictingEvidenceIds": list(self.contradicting_evidence_ids),
            "metrics": self.metrics.to_dict(),
            "eventId": self.event_id,
            "taskId": self.task_id,
            "failureReason": self.failure_reason,
            "sourcePath": self.source_path,
        }
