"""Day 7 黑盒评测运行器。

只使用 Python 标准库，通过真实 HTTP API 完成：
健康检查 -> 故障注入 -> 实验请求 -> Agent 诊断 -> 轮询 -> 原始结果持久化。

安全边界：
- 必须由 CLI 显式传入 --yes 才会进入真实执行路径；
- 不读取或写入 DEEPSEEK_API_KEY 等秘密；
- 诊断请求只携带 eventId，不携带 ground truth；
- 故障注入串行执行，并在 finally 中按 experimentId 清理；
- 不在本地重试 DeepSeek 诊断，也不调用诊断 retry 接口。
"""

from __future__ import annotations

import json
import os
import re
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Protocol, runtime_checkable
from urllib.parse import quote, urlsplit

from .http_client import HttpClient, HttpTransportError, UrllibHttpClient
from .models import (
    SCENARIO_DELAY,
    SCENARIO_HTTP_500,
    CaseSuite,
    Defaults,
    EvalCase,
    RawSample,
)

RAW_SAMPLE_SCHEMA_VERSION = "1.0"
RAW_SAMPLE_REQUIRED_FIELDS = (
    "schemaVersion",
    "sampleId",
    "caseId",
    "repeat",
    "observation",
    "diagnosis",
)
RAW_SAMPLE_OPTIONAL_FIELDS = (
    "harnessError",
    "startedAt",
    "completedAt",
    "runId",
    "environmentError",
    "apiError",
    "faultInjectionError",
    "executionError",
    "diagnosisError",
    "cleanupError",
    "budgetError",
    "faultEvent",
    "faultInjection",
)

TERMINAL_DIAGNOSIS_STATUSES = ("SUCCEEDED", "FAILED", "CANCELLED")
DIAGNOSIS_TIMEOUT_STATUS = "HARNESS_TIMEOUT"
DEFAULT_POLL_INTERVAL_SECONDS = 2.0
DEFAULT_HTTP_TIMEOUT_SECONDS = 30.0
DEFAULT_MAX_DIAGNOSIS_RUNS = 2


class BlackBoxEvaluationError(RuntimeError):
    """黑盒评测的可预期错误基类。"""


class EnvironmentCheckError(BlackBoxEvaluationError):
    """服务不可用、故障注入未启用或环境状态无法确认。"""


class FaultInjectionError(BlackBoxEvaluationError):
    """启用故障注入失败或返回状态与请求不一致。"""


class ApiCallError(BlackBoxEvaluationError):
    """HTTP 或 API 响应不符合预期。"""


class ExecutionError(BlackBoxEvaluationError):
    """实验执行响应缺少必要字段。"""


class DiagnosisError(BlackBoxEvaluationError):
    """诊断任务创建、查询或返回结构异常。"""


class CleanupError(BlackBoxEvaluationError):
    """故障注入清理失败。"""


class BudgetExceededError(BlackBoxEvaluationError):
    """诊断次数超过显式预算。"""


@dataclass(frozen=True)
class FaultStatus:
    experiment_enabled: bool
    active: bool
    experiment_id: Optional[str] = None
    fault_type: Optional[str] = None
    delay_ms: Optional[int] = None
    remaining_ms: Optional[int] = None


@dataclass(frozen=True)
class RunConfig:
    backend_url: str = "http://localhost:8080"
    order_url: str = "http://localhost:8081"
    inventory_url: str = "http://localhost:8082"
    results_dir: Path = Path("eval/results")
    run_id: Optional[str] = None
    repeats: int = 1
    max_diagnosis_runs: int = DEFAULT_MAX_DIAGNOSIS_RUNS
    poll_interval_seconds: float = DEFAULT_POLL_INTERVAL_SECONDS
    diagnosis_timeout_seconds: Optional[int] = None
    http_timeout_seconds: float = DEFAULT_HTTP_TIMEOUT_SECONDS

    def __post_init__(self) -> None:
        if self.repeats < 1:
            raise ValueError("repeats 必须大于等于 1")
        if self.max_diagnosis_runs < 0:
            raise ValueError("max_diagnosis_runs 不能小于 0")
        if self.poll_interval_seconds <= 0:
            raise ValueError("poll_interval_seconds 必须大于 0")
        if self.diagnosis_timeout_seconds is not None and self.diagnosis_timeout_seconds <= 0:
            raise ValueError("diagnosis_timeout_seconds 必须大于 0")
        if self.http_timeout_seconds <= 0:
            raise ValueError("http_timeout_seconds 必须大于 0")


@dataclass
class SuiteRunResult:
    run_id: str
    run_dir: Path
    samples: List[RawSample]
    report_paths: Dict[str, str]
    aborted: bool = False
    abort_reason: Optional[str] = None
    planned_diagnosis_runs: int = 0
    diagnosis_runs_started: int = 0


@runtime_checkable
class EvaluationRunner(Protocol):
    """黑盒 runner 的最小接口。"""

    def collect(self, case: EvalCase, repeat: int) -> RawSample:
        """执行一次场景并返回原始样本；不得把模型输出写入 ground truth。"""
        ...


class BlackBoxRunnerNotImplemented(RuntimeError):
    """保留给旧接口的占位异常；真实 runner 已由 BlackBoxEvaluationRunner 实现。"""


class PlaceholderEvaluationRunner:
    """占位实现，仅用于明确旧接口边界，调用即报错。"""

    def collect(self, case: EvalCase, repeat: int) -> RawSample:
        raise BlackBoxRunnerNotImplemented(
            "PlaceholderEvaluationRunner 不执行真实实验；"
            "请使用 BlackBoxEvaluationRunner")


def normalize_base_url(value: str) -> str:
    text = str(value or "").strip().rstrip("/")
    parsed = urlsplit(text)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise ValueError("服务地址必须是以 http:// 或 https:// 开头的有效 URL")
    if parsed.username or parsed.password:
        raise ValueError("服务地址不能包含用户名或密码")
    if parsed.query or parsed.fragment:
        raise ValueError("服务地址不能包含 query 或 fragment")
    return text


def validate_run_id(value: str) -> str:
    run_id = str(value or "").strip()
    if not run_id or ".." in run_id or not re.fullmatch(r"[A-Za-z0-9._-]+", run_id):
        raise ValueError("run-id 只能包含字母、数字、点、下划线和连字符，且不能包含 ..")
    return run_id


def make_run_id(now: Optional[datetime] = None) -> str:
    current = now or datetime.now(timezone.utc)
    if current.tzinfo is None:
        current = current.replace(tzinfo=timezone.utc)
    stamp = current.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return "run-%s-%s" % (stamp, uuid.uuid4().hex[:6])


def planned_diagnosis_runs(suite: CaseSuite, repeats: int) -> int:
    return sum(1 for case in suite.cases if case.diagnosis) * repeats


def validate_run_budget(suite: CaseSuite, repeats: int,
                        max_diagnosis_runs: int) -> int:
    if repeats < 1:
        raise ValueError("repeats 必须大于等于 1")
    if max_diagnosis_runs < 0:
        raise ValueError("max_diagnosis_runs 不能小于 0")
    planned = planned_diagnosis_runs(suite, repeats)
    if planned > max_diagnosis_runs:
        raise BudgetExceededError(
            "计划执行 %d 次 Agent 诊断，超过 --max-diagnosis-runs=%d；"
            "请降低 --repeats 或显式提高预算" % (planned, max_diagnosis_runs))
    return planned


def _atomic_write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + ".tmp")
    try:
        with temp.open("w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        os.replace(temp, path)
    finally:
        if temp.exists():
            temp.unlink()


def write_run_manifest(run_dir: Path, payload: Dict[str, Any]) -> Path:
    path = Path(run_dir) / "run-manifest.json"
    _atomic_write_json(path, payload)
    return path


def raw_sample_contract() -> Dict[str, Any]:
    """返回供 runner 作者和 README 使用的原始样本字段约定。"""

    return {
        "schemaVersion": RAW_SAMPLE_SCHEMA_VERSION,
        "requiredFields": list(RAW_SAMPLE_REQUIRED_FIELDS),
        "optionalFields": list(RAW_SAMPLE_OPTIONAL_FIELDS),
        "observationFields": [
            "faultObserved",
            "outcome",
            "httpStatus",
            "durationMs",
            "downstreamErrorType",
            "faultEventId",
            "requestId",
            "executedAt",
        ],
        "diagnosisFields": [
            "taskId",
            "status",
            "resultStatus",
            "failureReason",
            "report",
            "decisionTrace",
            "toolInvocations",
            "createdAt",
            "startedAt",
            "completedAt",
            "modelName",
        ],
        "errorFields": [
            "environmentError",
            "apiError",
            "faultInjectionError",
            "executionError",
            "diagnosisError",
            "cleanupError",
            "budgetError",
        ],
        "normalSample": "diagnosis 必须为 null；observation.faultEventId 必须为空。",
        "groundTruthRule": (
            "case 文件中的 expected 是唯一标签来源；runner 不得根据模型输出修改 case。"
        ),
        "rawOutputRule": (
            "每个样本写入 raw/<sampleId>.json；离线重判只读取这些文件。"
        ),
        "serialOnly": "真实运行串行执行；不得与其他故障注入实验并发。",
    }


class BlackBoxEvaluationRunner:
    """通过真实 HTTP API 执行单个场景。"""

    def __init__(self, config: RunConfig, raw_dir: Path, defaults: Defaults,
                 client: Optional[HttpClient] = None,
                 sleep: Optional[Callable[[float], None]] = None,
                 monotonic: Optional[Callable[[], float]] = None,
                 now: Optional[Callable[[], datetime]] = None) -> None:
        if not config.run_id:
            raise ValueError("RunConfig.run_id 不能为空")
        self.config = config
        self.run_id = validate_run_id(config.run_id)
        self.defaults = defaults
        self.backend_url = normalize_base_url(config.backend_url)
        self.order_url = normalize_base_url(config.order_url)
        self.inventory_url = normalize_base_url(config.inventory_url)
        self.run_dir = Path(config.results_dir) / self.run_id
        self.raw_dir = Path(raw_dir)
        self.raw_dir.mkdir(parents=True, exist_ok=True)
        self.client = client or UrllibHttpClient()
        self._sleep = sleep or time.sleep
        self._monotonic = monotonic or time.monotonic
        self._now = now or (lambda: datetime.now(timezone.utc))
        self.diagnosis_runs = 0
        self.last_raw: Optional[Dict[str, Any]] = None

    # ------------------------------------------------------------------ 预处理
    def preflight(self) -> Dict[str, Any]:
        """检查三个服务与故障注入状态；不修改任何故障状态。"""

        result: Dict[str, Any] = {
            "backend": self._check_health(
                self.backend_url, "backend", require_database=True),
            "order": self._check_health(self.order_url, "order-service"),
            "inventory": self._check_health(self.inventory_url, "inventory-service"),
        }
        try:
            status = self._get_fault_status()
        except ApiCallError as exc:
            raise EnvironmentCheckError(
                "无法确认故障注入状态，停止实验: %s" % exc) from exc
        if not status.experiment_enabled:
            raise EnvironmentCheckError(
                "故障注入功能未启用；请设置 EXPERIMENT_FAULT_INJECTION_ENABLED=true "
                "并重启 inventory-service，评测器不会自动修改配置")
        if status.active:
            raise EnvironmentCheckError(
                "检测到未关闭的故障注入（experimentId=%s, faultType=%s）；"
                "请先手动关闭或重置后再运行评测，评测器不会关闭非本次实验的注入"
                % (status.experiment_id, status.fault_type))
        result["faultInjection"] = {
            "experimentEnabled": status.experiment_enabled,
            "active": status.active,
            "faultType": status.fault_type,
            "experimentId": status.experiment_id,
        }
        return result

    def _check_health(self, base_url: str, name: str,
                      require_database: bool = False) -> Dict[str, Any]:
        try:
            data = self._api_data("GET", self._url(base_url, "/api/health"))
        except ApiCallError as exc:
            raise EnvironmentCheckError("%s 健康检查失败: %s" % (name, exc)) from exc
        status = str(data.get("status") or "").upper()
        if status != "UP":
            raise EnvironmentCheckError(
                "%s 健康状态为 %s，要求 UP" % (name, status or "UNKNOWN"))
        if require_database:
            database = data.get("database")
            if not isinstance(database, dict):
                raise EnvironmentCheckError("backend 健康响应缺少 database 状态")
            database_status = str(database.get("status") or "").upper()
            if database_status != "UP":
                raise EnvironmentCheckError(
                    "backend 数据库状态为 %s，要求 UP" % (database_status or "UNKNOWN"))
        return {
            "status": status,
            "service": data.get("service"),
            "version": data.get("version"),
        }

    # ------------------------------------------------------------------ 单样本
    def collect(self, case: EvalCase, repeat: int) -> RawSample:
        if repeat < 1:
            raise ValueError("repeat 必须大于等于 1")

        sample_id = self._sample_id(case.case_id, repeat)
        raw: Dict[str, Any] = {
            "schemaVersion": RAW_SAMPLE_SCHEMA_VERSION,
            "sampleId": sample_id,
            "caseId": case.case_id,
            "repeat": repeat,
            "runId": self.run_id,
            "observation": {},
            "diagnosis": None,
            "startedAt": self._iso_now(),
        }
        errors: Dict[str, str] = {}
        diagnosis: Optional[Dict[str, Any]] = None
        experiment_id: Optional[str] = None
        injection_attempted = False

        try:
            if case.diagnosis:
                if case.injection is None:
                    raise EnvironmentCheckError("诊断场景缺少故障注入配置")
                self._ensure_fault_inactive("场景开始前")
                injection_attempted = True
                status = self._enable_fault(case, repeat)
                experiment_id = status.experiment_id
                raw["faultInjection"] = {
                    "experimentId": status.experiment_id,
                    "faultType": status.fault_type,
                    "delayMs": status.delay_ms,
                    "remainingMs": status.remaining_ms,
                }
                execution = self._execute(case, experiment_id)
            else:
                self._ensure_fault_off_for_normal()
                execution = self._execute(case, None)

            raw["observation"] = execution
            if self.defaults.wait_after_execution_ms > 0:
                self._sleep(self.defaults.wait_after_execution_ms / 1000.0)

            if case.diagnosis:
                event_id = execution.get("faultEventId")
                if not event_id:
                    raise ExecutionError(
                        "故障场景未产生 faultEventId，无法启动 Agent 诊断")
                raw["faultEvent"] = self._get_fault_event(str(event_id))
                timeout_seconds = (
                    self.config.diagnosis_timeout_seconds
                    if self.config.diagnosis_timeout_seconds is not None
                    else self.defaults.diagnosis_timeout_seconds)
                diagnosis = self._diagnose(str(event_id), timeout_seconds)
                raw["diagnosis"] = diagnosis
        except BudgetExceededError as exc:
            errors["budgetError"] = str(exc)
        except EnvironmentCheckError as exc:
            errors["environmentError"] = str(exc)
        except FaultInjectionError as exc:
            errors["faultInjectionError"] = str(exc)
        except DiagnosisError as exc:
            errors["diagnosisError"] = str(exc)
        except ExecutionError as exc:
            errors["executionError"] = str(exc)
        except CleanupError as exc:
            errors["cleanupError"] = str(exc)
        except ApiCallError as exc:
            errors["apiError"] = str(exc)
        except Exception as exc:  # 不把 traceback 或敏感上下文写入原始结果
            errors["harnessError"] = "未预期异常: %s: %s" % (
                type(exc).__name__, exc)
        finally:
            if injection_attempted:
                try:
                    self._cleanup_fault(experiment_id, case)
                except Exception as exc:
                    errors["cleanupError"] = str(exc)
            raw["completedAt"] = self._iso_now()
            raw["diagnosis"] = diagnosis
            raw.update(errors)
            if errors:
                raw["harnessError"] = "; ".join(
                    "%s: %s" % (key, value) for key, value in errors.items())
            self.last_raw = raw
            try:
                raw_path = self._persist_raw(sample_id, raw)
            except OSError as exc:
                raise BlackBoxEvaluationError(
                    "原始样本持久化失败: %s" % exc) from exc

        return RawSample.from_dict(raw, source_path=str(raw_path))

    def cooldown(self) -> None:
        if self.defaults.cooldown_seconds > 0:
            self._sleep(self.defaults.cooldown_seconds)

    # ------------------------------------------------------------------ 故障控制
    def _ensure_fault_inactive(self, context: str) -> None:
        try:
            status = self._get_fault_status()
        except ApiCallError as exc:
            raise EnvironmentCheckError(
                "%s无法确认故障注入状态: %s" % (context, exc)) from exc
        if not status.experiment_enabled:
            raise EnvironmentCheckError(
                "故障注入功能未启用；请设置 EXPERIMENT_FAULT_INJECTION_ENABLED=true "
                "并重启 inventory-service")
        if status.active:
            raise EnvironmentCheckError(
                "%s存在未关闭的故障注入（experimentId=%s, faultType=%s），"
                "拒绝并发实验" % (context, status.experiment_id, status.fault_type))

    def _ensure_fault_off_for_normal(self) -> None:
        try:
            status = self._get_fault_status()
        except ApiCallError as exc:
            raise EnvironmentCheckError(
                "NORMAL 场景前无法确认故障注入已关闭: %s" % exc) from exc
        if not status.experiment_enabled:
            raise EnvironmentCheckError(
                "故障注入功能未启用；NORMAL 场景无法确认实验开关状态")
        if status.active:
            raise EnvironmentCheckError(
                "NORMAL 场景前故障注入仍处于激活状态（experimentId=%s, faultType=%s）"
                % (status.experiment_id, status.fault_type))

    def _get_fault_status(self) -> FaultStatus:
        data = self._api_data(
            "GET", self._url(self.backend_url, "/api/experiments/faults"))
        return self._fault_status_from_data(data, "查询故障注入状态")

    def _fault_status_from_data(self, data: Dict[str, Any],
                                context: str) -> FaultStatus:
        enabled = data.get("experimentEnabled")
        active = data.get("active")
        if not isinstance(enabled, bool) or not isinstance(active, bool):
            raise ApiCallError(
                "%s响应缺少布尔字段 experimentEnabled/active" % context)
        return FaultStatus(
            experiment_enabled=enabled,
            active=active,
            experiment_id=self._optional_str(data.get("experimentId")),
            fault_type=self._optional_str(data.get("faultType")),
            delay_ms=self._optional_int(data.get("delayMs")),
            remaining_ms=self._optional_int(data.get("remainingMs")),
        )

    def _enable_fault(self, case: EvalCase, repeat: int) -> FaultStatus:
        injection = case.injection
        if injection is None:
            raise FaultInjectionError("诊断场景缺少故障注入配置")
        if case.scenario == SCENARIO_HTTP_500:
            if injection.fault_type != "HTTP_500" or injection.delay_ms not in (None, 0):
                raise FaultInjectionError("HTTP_500 case 的故障注入配置不一致")
        elif case.scenario == SCENARIO_DELAY:
            if injection.fault_type != "DELAY" or not injection.delay_ms:
                raise FaultInjectionError("DELAY case 必须配置正数 delayMs")
        else:
            raise FaultInjectionError("未知诊断场景: %s" % case.scenario)

        payload = {
            "faultType": injection.fault_type,
            "delayMs": injection.delay_ms,
            "durationSeconds": self.defaults.fault_duration_seconds,
            # note 不写 caseId / 预期结果，避免任何 ground truth 进入服务端元数据。
            "note": "day7-eval run=%s repeat=%d" % (self.run_id, repeat),
        }
        try:
            data = self._api_data(
                "POST",
                self._url(self.backend_url, "/api/experiments/faults/enable"),
                payload,
            )
        except ApiCallError as exc:
            raise FaultInjectionError("启用故障注入失败: %s" % exc) from exc
        try:
            status = self._fault_status_from_data(data, "启用故障注入")
        except ApiCallError as exc:
            raise FaultInjectionError(str(exc)) from exc
        if not status.experiment_enabled or not status.active:
            raise FaultInjectionError("故障注入启用后未处于 active 状态")
        if not status.experiment_id:
            raise FaultInjectionError("故障注入启用后缺少 experimentId")
        if status.fault_type != injection.fault_type:
            raise FaultInjectionError(
                "故障注入类型不一致: 请求 %s，实际 %s"
                % (injection.fault_type, status.fault_type))
        if injection.delay_ms is not None and status.delay_ms != injection.delay_ms:
            raise FaultInjectionError(
                "故障注入延迟不一致: 请求 %s，实际 %s"
                % (injection.delay_ms, status.delay_ms))
        return status

    def _cleanup_fault(self, experiment_id: Optional[str], case: EvalCase) -> None:
        """只清理本次实验创建的注入；不关闭其他 experimentId。"""
        try:
            status = self._get_fault_status()
        except ApiCallError as exc:
            raise CleanupError("清理前无法确认故障注入状态: %s" % exc) from exc
        if not status.active:
            return
        if experiment_id is not None:
            if status.experiment_id != experiment_id:
                raise CleanupError(
                    "当前激活 experimentId=%s 不是本次实验 %s，拒绝关闭"
                    % (status.experiment_id, experiment_id))
        else:
            expected_type = case.injection.fault_type if case.injection else None
            if expected_type and status.fault_type != expected_type:
                raise CleanupError(
                    "当前激活 faultType=%s 与本次请求 %s 不一致，拒绝关闭"
                    % (status.fault_type, expected_type))
        self._disable_fault()

    def _disable_fault(self) -> FaultStatus:
        try:
            data = self._api_data(
                "POST",
                self._url(self.backend_url, "/api/experiments/faults/disable"),
            )
        except ApiCallError as exc:
            raise CleanupError("禁用故障注入失败: %s" % exc) from exc
        try:
            status = self._fault_status_from_data(data, "禁用故障注入")
        except ApiCallError as exc:
            raise CleanupError(str(exc)) from exc
        if status.active:
            raise CleanupError(
                "禁用故障注入后仍处于 active 状态（experimentId=%s）"
                % status.experiment_id)
        return status

    # ------------------------------------------------------------------ 实验执行
    def _execute(self, case: EvalCase,
                 experiment_id: Optional[str]) -> Dict[str, Any]:
        if case.injection is not None:
            if not experiment_id:
                raise ExecutionError("故障场景缺少 experimentId")
            path = "/api/experiments/%s/execute" % quote(experiment_id, safe="")
        else:
            path = "/api/experiments/checkout"
        payload = {
            "sku": self.defaults.sku,
            "quantity": self.defaults.quantity,
        }
        data = self._api_data("POST", self._url(self.backend_url, path), payload)
        return self._observation_from_execution(data)

    def _observation_from_execution(self, data: Dict[str, Any]) -> Dict[str, Any]:
        fault_observed = data.get("faultObserved")
        if not isinstance(fault_observed, bool):
            raise ExecutionError("执行响应缺少布尔字段 faultObserved")
        outcome = data.get("outcome")
        if not isinstance(outcome, str) or not outcome.strip():
            raise ExecutionError("执行响应缺少 outcome")
        http_status = data.get("httpStatus")
        if isinstance(http_status, bool) or not isinstance(http_status, int):
            raise ExecutionError("执行响应缺少整数 httpStatus")
        duration_ms = data.get("durationMs")
        if (isinstance(duration_ms, bool) or not isinstance(duration_ms, int)
                or duration_ms < 0):
            raise ExecutionError("执行响应缺少非负整数 durationMs")
        event_id = data.get("faultEventId")
        if event_id is not None and not isinstance(event_id, str):
            raise ExecutionError("执行响应 faultEventId 不是字符串")
        return {
            "faultObserved": fault_observed,
            "outcome": outcome.strip(),
            "httpStatus": http_status,
            "durationMs": duration_ms,
            "downstreamErrorType": self._optional_str(data.get("downstreamErrorType")),
            "faultEventId": event_id,
            "requestId": self._optional_str(data.get("requestId")),
            "orderStatus": self._optional_str(data.get("orderStatus")),
            "remainingStock": self._optional_int(data.get("remainingStock")),
            "executedAt": self._optional_str(data.get("executedAt")),
            "experimentId": self._optional_str(data.get("experimentId")),
            "presetFaultType": self._optional_str(data.get("presetFaultType")),
        }

    def _get_fault_event(self, event_id: str) -> Dict[str, Any]:
        path = "/api/fault-events/%s" % quote(event_id, safe="")
        try:
            data = self._api_data("GET", self._url(self.backend_url, path))
        except ApiCallError as exc:
            raise DiagnosisError("查询故障事件失败: %s" % exc) from exc
        event = data.get("event")
        if not isinstance(event, dict):
            raise DiagnosisError("故障事件详情响应缺少 event 对象")
        return {
            "id": event.get("id"),
            "serviceName": event.get("serviceName"),
            "eventType": event.get("eventType"),
            "severity": event.get("severity"),
            "status": event.get("status"),
            "occurredAt": event.get("occurredAt"),
            "requestId": event.get("requestId"),
        }

    # ------------------------------------------------------------------ 诊断
    def _diagnose(self, event_id: str, timeout_seconds: int) -> Dict[str, Any]:
        if timeout_seconds <= 0:
            raise DiagnosisError("诊断超时预算必须大于 0")
        if self.diagnosis_runs >= self.config.max_diagnosis_runs:
            raise BudgetExceededError(
                "已启动 %d 次诊断，达到 --max-diagnosis-runs=%d 上限"
                % (self.diagnosis_runs, self.config.max_diagnosis_runs))
        self.diagnosis_runs += 1

        path = "/api/fault-events/%s/diagnosis" % quote(event_id, safe="")
        try:
            detail = self._api_data("POST", self._url(self.backend_url, path))
        except ApiCallError as exc:
            raise DiagnosisError("启动诊断任务失败: %s" % exc) from exc
        task_id = self._optional_str(detail.get("id"))
        if not task_id:
            raise DiagnosisError("诊断任务响应缺少 id")

        deadline = self._monotonic() + float(timeout_seconds)
        last_detail = detail
        while True:
            status = str(last_detail.get("status") or "").upper()
            if status in TERMINAL_DIAGNOSIS_STATUSES:
                return self._diagnosis_payload(last_detail, task_id)
            remaining = deadline - self._monotonic()
            if remaining <= 0:
                return self._diagnosis_timeout_payload(
                    last_detail, task_id, timeout_seconds, status)
            self._sleep(min(self.config.poll_interval_seconds, remaining))
            try:
                last_detail = self._api_data(
                    "GET",
                    self._url(
                        self.backend_url,
                        "/api/diagnosis/tasks/%s" % quote(task_id, safe="")),
                )
            except ApiCallError as exc:
                raise DiagnosisError("轮询诊断任务失败: %s" % exc) from exc
            returned_id = self._optional_str(last_detail.get("id"))
            if returned_id and returned_id != task_id:
                raise DiagnosisError(
                    "轮询返回的 taskId=%s 与启动任务 %s 不一致"
                    % (returned_id, task_id))

    def _diagnosis_payload(self, detail: Dict[str, Any],
                           fallback_task_id: str) -> Dict[str, Any]:
        report_value = detail.get("report")
        report = report_value if isinstance(report_value, dict) else {}
        trace_value = detail.get("decisionTrace")
        trace = trace_value if isinstance(trace_value, list) else []
        tools_value = detail.get("toolInvocations")
        tools = tools_value if isinstance(tools_value, list) else []
        payload: Dict[str, Any] = {
            "taskId": self._optional_str(detail.get("id")) or fallback_task_id,
            "faultEventId": self._optional_str(detail.get("faultEventId")),
            "status": detail.get("status"),
            "resultStatus": detail.get("resultStatus"),
            "failureReason": detail.get("failureReason"),
            "report": report,
            "decisionTrace": trace,
            "toolInvocations": tools,
            "createdAt": detail.get("createdAt"),
            "startedAt": detail.get("startedAt"),
            "completedAt": detail.get("completedAt"),
            "modelName": detail.get("modelName"),
            "rootCause": detail.get("rootCause"),
            "confidenceLevel": detail.get("confidenceLevel"),
        }
        for key in (
                "hypothesis", "affectedServices", "supportingEvidenceIds",
                "contradictingEvidenceIds", "confidenceLevel", "rootCause"):
            if key in report and not payload.get(key):
                payload[key] = report[key]
        return payload

    def _diagnosis_timeout_payload(self, detail: Dict[str, Any], task_id: str,
                                   timeout_seconds: int,
                                   last_status: str) -> Dict[str, Any]:
        payload = self._diagnosis_payload(detail, task_id)
        payload["status"] = DIAGNOSIS_TIMEOUT_STATUS
        payload["resultStatus"] = None
        payload["failureReason"] = (
            "诊断任务在 %d 秒内未达到终态（最后状态: %s）"
            % (timeout_seconds, last_status or "UNKNOWN"))
        payload["lastObservedStatus"] = last_status
        payload["harnessTimedOutAt"] = self._iso_now()
        return payload

    # ------------------------------------------------------------------ HTTP 与工具
    def _api_data(self, method: str, url: str,
                  payload: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        try:
            response = self.client.request(
                method, url, payload=payload,
                timeout_seconds=self.config.http_timeout_seconds)
        except HttpTransportError as exc:
            raise ApiCallError("%s %s 网络请求失败: %s" % (method, url, exc)) from exc

        if response.status < 200 or response.status >= 300:
            message = self._response_message(response)
            raise ApiCallError(
                "%s %s 返回 HTTP %d: %s"
                % (method, url, response.status, message))
        try:
            body = response.json()
        except ValueError as exc:
            raise ApiCallError(
                "%s %s 响应不是合法 JSON" % (method, url)) from exc
        if not isinstance(body, dict):
            raise ApiCallError("%s %s 响应不是 JSON 对象" % (method, url))
        code = body.get("code")
        if code != 200:
            raise ApiCallError(
                "%s %s 返回业务错误 code=%s: %s"
                % (method, url, code, self._truncate(body.get("message"), 500)))
        data = body.get("data")
        if not isinstance(data, dict):
            raise ApiCallError("%s %s 响应 data 不是对象" % (method, url))
        return data

    def _response_message(self, response: Any) -> str:
        try:
            body = response.json()
        except ValueError:
            text = (response.body or "").strip().replace("\n", " ")
            return self._truncate(text, 300) or "(空响应)"
        if isinstance(body, dict):
            message = body.get("message")
            if message:
                return self._truncate(message, 500)
        return "(无错误消息)"

    def _url(self, base_url: str, path: str) -> str:
        return base_url.rstrip("/") + "/" + path.lstrip("/")

    def _sample_id(self, case_id: str, repeat: int) -> str:
        candidate = "%s-%s-r%d" % (self.run_id, case_id, repeat)
        cleaned = re.sub(r"[^A-Za-z0-9._-]+", "-", candidate).strip("-")
        return cleaned or ("sample-r%d" % repeat)

    def _persist_raw(self, sample_id: str, raw: Dict[str, Any]) -> Path:
        path = self.raw_dir / (sample_id + ".json")
        _atomic_write_json(path, raw)
        return path

    def _iso_now(self) -> str:
        value = self._now()
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc).isoformat(
            timespec="seconds").replace("+00:00", "Z")

    @staticmethod
    def _optional_str(value: Any) -> Optional[str]:
        if value is None:
            return None
        text = str(value).strip()
        return text or None

    @staticmethod
    def _optional_int(value: Any) -> Optional[int]:
        if isinstance(value, bool) or value is None:
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

    @staticmethod
    def _truncate(value: Any, limit: int) -> str:
        if value is None:
            return ""
        text = str(value)
        return text if len(text) <= limit else text[:limit] + "..."


# ---------------------------------------------------------------------- 批量运行
def run_suite(suite: CaseSuite, runner: BlackBoxEvaluationRunner,
              config: RunConfig,
              preflight: Optional[Dict[str, Any]] = None) -> SuiteRunResult:
    """串行执行 suite，逐样本持久化，最后复用离线判分和报告生成。"""

    planned = validate_run_budget(
        suite, config.repeats, config.max_diagnosis_runs)
    from .report import write_reports
    from .scoring import score_all

    started_at = runner._iso_now()
    samples: List[RawSample] = []
    aborted = False
    abort_reason: Optional[str] = None

    for case in suite.cases:
        for repeat in range(1, config.repeats + 1):
            sample = runner.collect(case, repeat)
            samples.append(sample)
            if sample.harness_error:
                aborted = True
                abort_reason = sample.harness_error
                break
            runner.cooldown()
        if aborted:
            break

    results = score_all(suite, samples)
    report_paths = write_reports(
        runner.run_dir / "report",
        suite,
        results,
        manifest_extra={
            "mode": "black-box-http",
            "runId": runner.run_id,
            "networkCalls": True,
            "modelCalls": runner.diagnosis_runs > 0,
            "diagnosisRunsStarted": runner.diagnosis_runs,
            "aborted": aborted,
            "abortReason": abort_reason,
            "backendUrl": runner.backend_url,
            "orderUrl": runner.order_url,
            "inventoryUrl": runner.inventory_url,
            "dataNote": (
                "本报告复用离线判分规则处理本次真实黑盒运行产生的原始样本；"
                "模型调用仅通过后端 Agent 诊断接口间接发生。"
            ),
        },
    )

    completed_at = runner._iso_now()
    manifest_payload = {
        "runId": runner.run_id,
        "startedAt": started_at,
        "completedAt": completed_at,
        "mode": "black-box-http",
        "networkCalls": True,
        "directModelCalls": False,
        "plannedDiagnosisRuns": planned,
        "diagnosisRunsStarted": runner.diagnosis_runs,
        "sampleCount": len(samples),
        "aborted": aborted,
        "abortReason": abort_reason,
        "preflight": preflight,
        "environment": {
            "backendUrl": runner.backend_url,
            "orderUrl": runner.order_url,
            "inventoryUrl": runner.inventory_url,
            "repeats": config.repeats,
            "maxDiagnosisRuns": config.max_diagnosis_runs,
            "pollIntervalSeconds": config.poll_interval_seconds,
            "diagnosisTimeoutSeconds": (
                config.diagnosis_timeout_seconds
                if config.diagnosis_timeout_seconds is not None
                else runner.defaults.diagnosis_timeout_seconds),
        },
        "endpoints": [
            "GET /api/health",
            "GET /api/experiments/faults",
            "POST /api/experiments/faults/enable",
            "POST /api/experiments/faults/disable",
            "POST /api/experiments/{experimentId}/execute",
            "POST /api/experiments/checkout",
            "GET /api/fault-events/{eventId}",
            "POST /api/fault-events/{eventId}/diagnosis",
            "GET /api/diagnosis/tasks/{taskId}",
        ],
        "retryPolicy": "诊断任务只在本地轮询；不调用 retry 接口，不自动重启失败的诊断。",
        "dataNote": (
            "原始结果由真实 HTTP API 产生；模型调用只通过后端 Agent 诊断接口间接发生，"
            "评测器未读取 DEEPSEEK_API_KEY。"
        ),
    }
    write_run_manifest(runner.run_dir, manifest_payload)

    return SuiteRunResult(
        run_id=runner.run_id,
        run_dir=runner.run_dir,
        samples=samples,
        report_paths=report_paths,
        aborted=aborted,
        abort_reason=abort_reason,
        planned_diagnosis_runs=planned,
        diagnosis_runs_started=runner.diagnosis_runs,
    )
