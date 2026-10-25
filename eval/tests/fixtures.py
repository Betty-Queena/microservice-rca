"""测试用合成样本构造器。

这些数据仅用于单元测试，不是真实实验结果，也不会写入 eval/results/。
"""

from __future__ import annotations

import copy
import sys
from pathlib import Path
from typing import Any, Dict, Optional

TESTS_DIR = Path(__file__).resolve().parent
EVAL_DIR = TESTS_DIR.parent
if str(EVAL_DIR) not in sys.path:
    sys.path.insert(0, str(EVAL_DIR))

from rca_eval.cases import load_suite
from rca_eval.models import CaseSuite, RawSample

SUITE_PATH = EVAL_DIR / "cases" / "day7-baseline.json"


def baseline_suite() -> CaseSuite:
    return load_suite(SUITE_PATH)


def find_case(suite: CaseSuite, case_id: str):
    for case in suite.cases:
        if case.case_id == case_id:
            return case
    raise AssertionError("case not found: %s" % case_id)


def http500_observation(**overrides: Any) -> Dict[str, Any]:
    data: Dict[str, Any] = {
        "faultObserved": True,
        "outcome": "DOWNSTREAM_HTTP_5XX",
        "httpStatus": 502,
        "durationMs": 120,
        "downstreamErrorType": "HTTP_5XX",
        "faultEventId": "event-fixture-1",
    }
    data.update(overrides)
    return data


def delay_observation(**overrides: Any) -> Dict[str, Any]:
    data: Dict[str, Any] = {
        "faultObserved": True,
        "outcome": "SLOW_RESPONSE",
        "httpStatus": 200,
        "durationMs": 3600,
        "faultEventId": "event-fixture-2",
    }
    data.update(overrides)
    return data


def normal_observation(**overrides: Any) -> Dict[str, Any]:
    data: Dict[str, Any] = {
        "faultObserved": False,
        "outcome": "SUCCESS",
        "httpStatus": 200,
        "durationMs": 80,
        "faultEventId": None,
    }
    data.update(overrides)
    return data


def diagnosis_payload(**overrides: Any) -> Dict[str, Any]:
    data: Dict[str, Any] = {
        "taskId": "task-fixture-1",
        "status": "SUCCEEDED",
        "resultStatus": "ROOT_CAUSE_IDENTIFIED",
        "failureReason": None,
        "report": {
            "rootCause": "inventory-service 返回 HTTP 500，导致 order-service 返回 502",
            "hypothesis": "库存服务内部错误",
            "affectedServices": ["inventory-service"],
            "supportingEvidenceIds": ["log:inventory-service:log-1"],
            "contradictingEvidenceIds": [],
            "confidenceLevel": "HIGH",
            "recommendedActions": ["检查库存服务"],
            "limitations": ["仅基于当前证据"],
        },
        "decisionTrace": [
            {
                "type": "MODEL_DECISION",
                "modelCallAttempted": True,
                "usageAvailable": True,
                "promptTokens": 100,
                "completionTokens": 20,
                "latencyMs": 500,
            },
            {
                "type": "TOOL_EXECUTION",
                "status": "SUCCESS",
                "latencyMs": 100,
            },
            {
                "type": "AGENT_RUN_SUMMARY",
                "modelCallCount": 1,
                "modelCallsWithUsage": 1,
                "modelCallsWithoutUsage": 0,
                "promptTokens": 100,
                "completionTokens": 20,
                "totalTokens": 120,
                "modelLatencyMsTotal": 500,
            },
        ],
        "toolInvocations": [
            {"toolName": "query_logs", "status": "SUCCESS", "latencyMs": 100}
        ],
        "createdAt": "2026-10-24T10:00:00Z",
        "startedAt": "2026-10-24T10:00:01Z",
        "completedAt": "2026-10-24T10:00:18Z",
    }
    data.update(overrides)
    if "report" in overrides and isinstance(overrides["report"], dict):
        data["report"] = overrides["report"]
    return data


def make_sample(case_id: str, observation: Dict[str, Any],
                diagnosis: Optional[Dict[str, Any]] = None,
                repeat: int = 1, **overrides: Any) -> RawSample:
    data: Dict[str, Any] = {
        "schemaVersion": "1.0",
        "sampleId": "fixture-%s-r%d" % (case_id, repeat),
        "caseId": case_id,
        "repeat": repeat,
        "observation": copy.deepcopy(observation),
        "diagnosis": copy.deepcopy(diagnosis) if diagnosis is not None else None,
        "startedAt": "2026-10-24T10:00:00Z",
        "completedAt": "2026-10-24T10:00:20Z",
    }
    data.update(overrides)
    return RawSample.from_dict(data, source_path="fixture.json")
