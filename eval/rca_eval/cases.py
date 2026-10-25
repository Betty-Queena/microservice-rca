"""case 文件加载、ground truth 校验与原始结果读取。"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

from .models import (
    KNOWN_SCENARIOS,
    KNOWN_SERVICES,
    CaseSuite,
    CaseValidationError,
    Defaults,
    EvalCase,
    ExpectedResult,
    Injection,
    JudgeRule,
    RawSample,
    SCENARIO_DELAY,
    SCENARIO_HTTP_500,
    SCENARIO_NORMAL,
)


def _read_str(data: Dict[str, Any], key: str, path: str, errors: List[str],
              required: bool = True, default: Optional[str] = None) -> Optional[str]:
    if key not in data or data[key] is None:
        if required:
            errors.append("%s.%s 缺失" % (path, key))
        return default
    value = data[key]
    if not isinstance(value, str) or not value.strip():
        errors.append("%s.%s 必须是非空字符串" % (path, key))
        return default
    return value.strip()


def _read_bool(data: Dict[str, Any], key: str, path: str, errors: List[str],
               required: bool = True, default: Optional[bool] = None) -> Optional[bool]:
    if key not in data or data[key] is None:
        if required:
            errors.append("%s.%s 缺失" % (path, key))
        return default
    value = data[key]
    if not isinstance(value, bool):
        errors.append("%s.%s 必须是布尔值" % (path, key))
        return default
    return value


def _read_int(data: Dict[str, Any], key: str, path: str, errors: List[str],
              required: bool = True, default: Optional[int] = None) -> Optional[int]:
    if key not in data or data[key] is None:
        if required:
            errors.append("%s.%s 缺失" % (path, key))
        return default
    value = data[key]
    if isinstance(value, bool) or not isinstance(value, int):
        errors.append("%s.%s 必须是整数" % (path, key))
        return default
    return value


def _read_number(data: Dict[str, Any], key: str, path: str, errors: List[str],
                 required: bool = True, default: Optional[float] = None) -> Optional[float]:
    if key not in data or data[key] is None:
        if required:
            errors.append("%s.%s 缺失" % (path, key))
        return default
    value = data[key]
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        errors.append("%s.%s 必须是数字" % (path, key))
        return default
    return float(value)


def _read_list_str(data: Dict[str, Any], key: str, path: str, errors: List[str],
                   required: bool = True) -> Tuple[str, ...]:
    if key not in data or data[key] is None:
        if required:
            errors.append("%s.%s 缺失" % (path, key))
        return ()
    value = data[key]
    if not isinstance(value, list):
        errors.append("%s.%s 必须是字符串数组" % (path, key))
        return ()
    result: List[str] = []
    for index, item in enumerate(value):
        if not isinstance(item, str) or not item.strip():
            errors.append("%s.%s[%d] 必须是非空字符串" % (path, key, index))
            continue
        result.append(item.strip())
    if required and not result:
        errors.append("%s.%s 不能为空" % (path, key))
    return tuple(result)


def _read_dict(data: Dict[str, Any], key: str, path: str, errors: List[str],
               required: bool = True) -> Optional[Dict[str, Any]]:
    if key not in data or data[key] is None:
        if required:
            errors.append("%s.%s 缺失" % (path, key))
        return None
    value = data[key]
    if not isinstance(value, dict):
        errors.append("%s.%s 必须是对象" % (path, key))
        return None
    return dict(value)


def _parse_injection(data: Dict[str, Any], path: str, errors: List[str]) -> Optional[Injection]:
    fault_type = _read_str(data, "faultType", path, errors)
    delay_ms = _read_int(data, "delayMs", path, errors, required=False)
    if delay_ms is not None and delay_ms < 0:
        errors.append("%s.delayMs 不能为负数" % path)
    if not fault_type:
        return None
    return Injection(fault_type=fault_type.upper(), delay_ms=delay_ms)


def _parse_expected(data: Dict[str, Any], path: str, errors: List[str]) -> ExpectedResult:
    return ExpectedResult(
        fault_observed=bool(_read_bool(data, "faultObserved", path, errors)),
        outcome=_read_str(data, "outcome", path, errors) or "",
        http_status=_read_int(data, "httpStatus", path, errors, required=False),
        min_duration_ms=_read_int(data, "minDurationMs", path, errors, required=False),
        event_expected=bool(_read_bool(data, "eventExpected", path, errors)),
        root_cause_service=_read_str(
            data, "rootCauseService", path, errors, required=False),
        fault_category=_read_str(data, "faultCategory", path, errors) or "",
    )


def _parse_judge(data: Dict[str, Any], path: str, errors: List[str]) -> JudgeRule:
    min_matchers = _read_int(
        data, "minEvidenceMatchers", path, errors, required=False, default=1)
    return JudgeRule(
        service_aliases=_read_list_str(data, "serviceAliases", path, errors),
        category_keywords=_read_list_str(data, "categoryKeywords", path, errors),
        risk_keywords=_read_list_str(data, "riskKeywords", path, errors, required=False),
        evidence_matchers=_read_list_str(data, "evidenceMatchers", path, errors),
        min_evidence_matchers=1 if min_matchers is None else min_matchers,
    )


def _parse_case(data: Dict[str, Any], index: int, errors: List[str]) -> EvalCase:
    path = "cases[%d]" % index
    case_id = _read_str(data, "caseId", path, errors) or ""
    scenario = (_read_str(data, "scenario", path, errors) or "").upper()
    diagnosis = bool(_read_bool(data, "diagnosis", path, errors))
    notes = _read_str(data, "notes", path, errors, required=False, default="") or ""

    expected_data = _read_dict(data, "expected", path, errors)
    expected = _parse_expected(expected_data or {}, path + ".expected", errors) \
        if expected_data is not None else ExpectedResult(
            fault_observed=False, outcome="", event_expected=False)

    injection_data = data.get("injection")
    injection = None
    if injection_data is not None:
        if not isinstance(injection_data, dict):
            errors.append("%s.injection 必须是对象或 null" % path)
        else:
            injection = _parse_injection(injection_data, path + ".injection", errors)
    elif diagnosis:
        errors.append("%s.injection 缺失" % path)

    judge_data = data.get("judge")
    judge = None
    if judge_data is not None:
        if not isinstance(judge_data, dict):
            errors.append("%s.judge 必须是对象或 null" % path)
        else:
            judge = _parse_judge(judge_data, path + ".judge", errors)

    if scenario not in KNOWN_SCENARIOS:
        errors.append("%s.scenario 必须是 %s 之一" % (path, ", ".join(KNOWN_SCENARIOS)))

    if expected.http_status is not None and not (100 <= expected.http_status <= 599):
        errors.append("%s.expected.httpStatus 必须在 100..599 之间" % path)
    if expected.min_duration_ms is not None and expected.min_duration_ms < 0:
        errors.append("%s.expected.minDurationMs 不能为负数" % path)

    if scenario == SCENARIO_NORMAL:
        if diagnosis:
            errors.append("%s 正常场景不能启用 Agent 诊断" % path)
        if injection is not None:
            errors.append("%s 正常场景不能配置故障注入" % path)
        if expected.fault_observed:
            errors.append("%s 正常场景的 faultObserved 必须为 false" % path)
        if expected.event_expected:
            errors.append("%s 正常场景不能期望生成故障事件" % path)
        if judge is not None:
            errors.append("%s 正常场景不参与根因判分，judge 必须为 null" % path)
        if expected.root_cause_service is not None:
            errors.append("%s 正常场景不能设置 rootCauseService" % path)
        if expected.fault_category.upper() != "NONE":
            errors.append("%s 正常场景的 faultCategory 必须为 NONE" % path)

    if scenario == SCENARIO_HTTP_500:
        if injection is None:
            errors.append("%s HTTP_500 场景缺少 injection" % path)
        else:
            if injection.fault_type != SCENARIO_HTTP_500:
                errors.append("%s injection.faultType 必须是 HTTP_500" % path)
            if injection.delay_ms not in (None, 0):
                errors.append("%s HTTP_500 场景不能设置 delayMs" % path)

    if scenario == SCENARIO_DELAY:
        if injection is None:
            errors.append("%s DELAY 场景缺少 injection" % path)
        else:
            if injection.fault_type != SCENARIO_DELAY:
                errors.append("%s injection.faultType 必须是 DELAY" % path)
            if injection.delay_ms is None or injection.delay_ms <= 0:
                errors.append("%s DELAY 场景必须设置正数 delayMs" % path)

    if diagnosis:
        if scenario == SCENARIO_NORMAL:
            errors.append("%s 正常场景不能启用诊断" % path)
        if not expected.fault_observed:
            errors.append("%s 诊断用例必须期望 faultObserved=true" % path)
        if not expected.event_expected:
            errors.append("%s 诊断用例必须期望生成故障事件" % path)
        if expected.root_cause_service not in KNOWN_SERVICES:
            errors.append("%s.expected.rootCauseService 必须是 %s 之一" % (
                path, ", ".join(KNOWN_SERVICES)))
        if not expected.fault_category or expected.fault_category.upper() == "NONE":
            errors.append("%s 诊断用例必须设置非 NONE 的 faultCategory" % path)
        if judge is None:
            errors.append("%s 诊断用例缺少 judge 规则" % path)
        else:
            if not judge.service_aliases:
                errors.append("%s.judge.serviceAliases 不能为空" % path)
            if not judge.category_keywords:
                errors.append("%s.judge.categoryKeywords 不能为空" % path)
            if not judge.evidence_matchers:
                errors.append("%s.judge.evidenceMatchers 不能为空" % path)
            if not (1 <= judge.min_evidence_matchers <= len(judge.evidence_matchers)):
                errors.append("%s.judge.minEvidenceMatchers 必须在 1..%d 之间" % (
                    path, len(judge.evidence_matchers)))
    else:
        if expected.event_expected:
            errors.append("%s 未启用诊断的用例不应期望事件" % path)
        if judge is not None:
            errors.append("%s 未启用诊断的用例 judge 必须为 null" % path)

    return EvalCase(
        case_id=case_id,
        scenario=scenario,
        injection=injection,
        expected=expected,
        judge=judge,
        diagnosis=diagnosis,
        notes=notes,
    )


def _parse_defaults(data: Optional[Dict[str, Any]], errors: List[str]) -> Defaults:
    if data is None:
        errors.append("defaults 缺失")
        return Defaults()
    repeat = _read_int(data, "repeat", "defaults", errors, required=False, default=1)
    cooldown = _read_number(
        data, "cooldownSeconds", "defaults", errors, required=False, default=5.0)
    wait_after = _read_int(
        data, "waitAfterExecutionMs", "defaults", errors, required=False, default=2000)
    fault_duration = _read_int(
        data, "faultDurationSeconds", "defaults", errors, required=False, default=120)
    sku = _read_str(data, "sku", "defaults", errors, required=False, default="SKU-1001")
    quantity = _read_int(data, "quantity", "defaults", errors, required=False, default=1)
    diagnosis_timeout = _read_int(
        data, "diagnosisTimeoutSeconds", "defaults", errors, required=False, default=240)
    return Defaults(
        repeat=1 if repeat is None else repeat,
        cooldown_seconds=5.0 if cooldown is None else cooldown,
        wait_after_execution_ms=2000 if wait_after is None else wait_after,
        fault_duration_seconds=120 if fault_duration is None else fault_duration,
        sku="SKU-1001" if sku is None else sku,
        quantity=1 if quantity is None else quantity,
        diagnosis_timeout_seconds=240 if diagnosis_timeout is None else diagnosis_timeout,
    )


def suite_from_dict(data: Any, source_path: Optional[str] = None) -> CaseSuite:
    errors: List[str] = []
    if not isinstance(data, dict):
        raise CaseValidationError(["case 文件根节点必须是对象"])

    suite_id = _read_str(data, "suiteId", "root", errors) or ""
    label_version = _read_str(data, "labelVersion", "root", errors) or ""
    label_source = _read_str(data, "labelSource", "root", errors) or ""
    defaults = _parse_defaults(_read_dict(data, "defaults", "root", errors), errors)
    environment = _read_dict(
        data, "environmentRequirements", "root", errors, required=False) or {}

    raw_cases = data.get("cases")
    parsed_cases: List[EvalCase] = []
    if not isinstance(raw_cases, list) or not raw_cases:
        errors.append("cases 必须是非空数组")
    else:
        for index, item in enumerate(raw_cases):
            if not isinstance(item, dict):
                errors.append("cases[%d] 必须是对象" % index)
                continue
            parsed_cases.append(_parse_case(item, index, errors))

    seen: set = set()
    for case in parsed_cases:
        if case.case_id in seen:
            errors.append("caseId 重复: %s" % case.case_id)
        seen.add(case.case_id)

    if defaults.repeat < 1:
        errors.append("defaults.repeat 必须大于等于 1")
    if defaults.cooldown_seconds < 0:
        errors.append("defaults.cooldownSeconds 不能为负数")
    if defaults.wait_after_execution_ms < 0:
        errors.append("defaults.waitAfterExecutionMs 不能为负数")
    if defaults.fault_duration_seconds <= 0:
        errors.append("defaults.faultDurationSeconds 必须大于 0")
    if defaults.quantity < 1:
        errors.append("defaults.quantity 必须大于等于 1")
    if defaults.diagnosis_timeout_seconds <= 0:
        errors.append("defaults.diagnosisTimeoutSeconds 必须大于 0")

    if errors:
        raise CaseValidationError(errors)

    return CaseSuite(
        suite_id=suite_id,
        label_version=label_version,
        label_source=label_source,
        defaults=defaults,
        environment_requirements=environment,
        cases=tuple(parsed_cases),
        source_path=source_path,
    )


def load_suite(path: Any) -> CaseSuite:
    suite_path = Path(path)
    if not suite_path.is_file():
        raise CaseValidationError(["case 文件不存在: %s" % suite_path])
    try:
        with suite_path.open("r", encoding="utf-8") as handle:
            data = json.load(handle)
    except json.JSONDecodeError as exc:
        raise CaseValidationError([
            "case 文件 JSON 解析失败: %s:%d: %s" % (suite_path, exc.lineno, exc.msg)])
    return suite_from_dict(data, source_path=str(suite_path))


def suite_warnings(suite: CaseSuite) -> List[str]:
    warnings: List[str] = []
    for case in suite.cases:
        if case.diagnosis and case.judge is not None and not case.judge.risk_keywords:
            warnings.append("%s 未配置 riskKeywords，否定/误报风险只能人工复核" % case.case_id)
        if case.scenario == SCENARIO_DELAY and case.expected.min_duration_ms is not None:
            threshold = suite.environment_requirements.get("backendSlowResponseThresholdMs")
            if isinstance(threshold, int) and case.expected.min_duration_ms < threshold:
                warnings.append(
                    "%s 的 minDurationMs=%d 小于慢响应阈值 %d，可能无法触发 SLOW_RESPONSE" % (
                        case.case_id, case.expected.min_duration_ms, threshold))

    order_timeout = suite.environment_requirements.get("orderReadTimeoutMs")
    max_delay = suite.environment_requirements.get("inventoryMaxDelayMs")
    if isinstance(order_timeout, int) and isinstance(max_delay, int):
        if max_delay <= order_timeout:
            warnings.append(
                "inventoryMaxDelayMs(%d) <= orderReadTimeoutMs(%d)，默认配置无法产生 "
                "DOWNSTREAM_TIMEOUT；P0 不应包含超时场景" % (max_delay, order_timeout))
    return warnings


def _load_json_file(path: Path) -> Any:
    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle)
    except json.JSONDecodeError as exc:
        raise CaseValidationError([
            "原始结果 JSON 解析失败: %s:%d: %s" % (path, exc.lineno, exc.msg)])


def _samples_from_payload(payload: Any, source_path: str) -> List[RawSample]:
    result: List[RawSample] = []
    stem = Path(source_path).stem
    if isinstance(payload, list):
        for index, item in enumerate(payload):
            result.append(RawSample.from_dict(
                item, source_path=source_path,
                fallback_sample_id="%s#%d" % (stem, index)))
        return result
    if isinstance(payload, dict):
        samples = payload.get("samples")
        if isinstance(samples, list):
            for index, item in enumerate(samples):
                result.append(RawSample.from_dict(
                    item, source_path=source_path,
                    fallback_sample_id="%s#%d" % (stem, index)))
        else:
            result.append(RawSample.from_dict(
                payload, source_path=source_path, fallback_sample_id=stem))
        return result
    return [RawSample.from_dict(payload, source_path=source_path, fallback_sample_id=stem)]


def load_raw_samples(path: Any) -> List[RawSample]:
    """读取本地原始结果，不调用任何服务。

    支持：
    - 单目录：优先读取 ``raw/*.json``，否则读取 ``samples.jsonl``；
    - 单个 ``.json`` 文件：单个样本、样本数组，或带 ``samples`` 数组的运行清单；
    - ``.jsonl`` 文件：每行一个样本。
    """

    input_path = Path(path)
    if not input_path.exists():
        raise CaseValidationError(["原始结果路径不存在: %s" % input_path])

    samples: List[RawSample] = []
    if input_path.is_dir():
        raw_dir = input_path / "raw"
        jsonl_path = input_path / "samples.jsonl"
        if raw_dir.is_dir():
            for json_file in sorted(raw_dir.glob("*.json")):
                samples.extend(_samples_from_payload(
                    _load_json_file(json_file), str(json_file)))
            if not samples and jsonl_path.is_file():
                samples.extend(_load_jsonl(jsonl_path))
        elif jsonl_path.is_file():
            samples.extend(_load_jsonl(jsonl_path))
        else:
            for json_file in sorted(input_path.glob("*.json")):
                samples.extend(_samples_from_payload(
                    _load_json_file(json_file), str(json_file)))
    elif input_path.suffix.lower() == ".jsonl":
        samples.extend(_load_jsonl(input_path))
    elif input_path.suffix.lower() == ".json":
        samples.extend(_samples_from_payload(_load_json_file(input_path), str(input_path)))
    else:
        raise CaseValidationError([
            "原始结果必须是目录、.json 或 .jsonl 文件: %s" % input_path])

    if not samples:
        raise CaseValidationError(["未在 %s 找到任何原始样本" % input_path])
    return samples


def _load_jsonl(path: Path) -> List[RawSample]:
    samples: List[RawSample] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            text = line.strip()
            if not text:
                continue
            try:
                payload = json.loads(text)
            except json.JSONDecodeError as exc:
                raise CaseValidationError([
                    "原始结果 JSONL 解析失败: %s:%d: %s" % (path, line_no, exc.msg)])
            samples.append(RawSample.from_dict(
                payload, source_path="%s:%d" % (path, line_no),
                fallback_sample_id="%s#%d" % (path.stem, line_no)))
    return samples


def index_cases(suite: CaseSuite) -> Dict[str, EvalCase]:
    return {case.case_id: case for case in suite.cases}


def case_ids(suite: CaseSuite) -> Sequence[str]:
    return tuple(case.case_id for case in suite.cases)
