"""黑盒 HTTP runner 测试。

所有 HTTP 响应都是内存中的合成 fixture，不访问真实服务，也不调用模型。
"""

from __future__ import annotations

import copy
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import urlsplit

EVAL_DIR = Path(__file__).resolve().parents[1]
if str(EVAL_DIR) not in sys.path:
    sys.path.insert(0, str(EVAL_DIR))

import run_eval
from rca_eval.http_client import HttpResponse
from rca_eval.models import CaseSuite
from rca_eval.runner import (
    BlackBoxEvaluationRunner,
    BudgetExceededError,
    EnvironmentCheckError,
    RunConfig,
    planned_diagnosis_runs,
    run_suite,
    validate_run_budget,
)
from tests.fixtures import (
    baseline_suite,
    delay_observation,
    diagnosis_payload,
    find_case,
    http500_observation,
    normal_observation,
)


def _ok(data: Any) -> HttpResponse:
    return HttpResponse(
        status=200,
        headers={"content-type": "application/json"},
        body=json.dumps(
            {"code": 200, "message": "OK", "data": data, "timestamp": "2026-10-24T00:00:00Z"},
            ensure_ascii=False),
    )


def _api_error(status: int, message: str) -> HttpResponse:
    return HttpResponse(
        status=status,
        headers={"content-type": "application/json"},
        body=json.dumps(
            {"code": status, "message": message, "data": None,
             "timestamp": "2026-10-24T00:00:00Z"},
            ensure_ascii=False),
    )


def _path(url: str) -> str:
    return urlsplit(url).path


class FakeClock:
    def __init__(self, start: float = 1000.0) -> None:
        self.current = start
        self.sleeps: List[float] = []

    def monotonic(self) -> float:
        return self.current

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.current += seconds


class FakeHttpClient:
    def __init__(self, handler) -> None:
        self._handler = handler
        self.calls: List[Tuple[str, str, Optional[Dict[str, Any]]]] = []

    def request(self, method: str, url: str,
                payload: Optional[Dict[str, Any]] = None,
                timeout_seconds: float = 30.0) -> HttpResponse:
        self.calls.append((method, url, copy.deepcopy(payload)))
        return self._handler(method, url, payload)

    def paths(self) -> List[str]:
        return [_path(url) for _, url, _ in self.calls]


def _success_diagnosis_state() -> Dict[str, Any]:
    payload = diagnosis_payload()
    payload["id"] = "task-1"
    payload["faultEventId"] = "event-fixture-1"
    return payload


class FakeBackend:
    """模拟 backend 的故障/实验/诊断接口，不包含真实 HTTP。"""

    def __init__(self, execute_data: Dict[str, Any],
                 diagnosis_states: Optional[List[Dict[str, Any]]] = None,
                 enable_failure_message: Optional[str] = None,
                 disable_failure_message: Optional[str] = None,
                 experiment_enabled: bool = True,
                 execute_exception: Optional[Exception] = None) -> None:
        self.execute_data = execute_data
        self.diagnosis_states = list(diagnosis_states or [])
        self.enable_failure_message = enable_failure_message
        self.disable_failure_message = disable_failure_message
        self.experiment_enabled = experiment_enabled
        self.execute_exception = execute_exception
        self.active = False
        self.experiment_id: Optional[str] = None
        self.fault_type: Optional[str] = None
        self.delay_ms: Optional[int] = None
        self.diagnosis_poll_count = 0

    def fault_status(self) -> Dict[str, Any]:
        return {
            "experimentEnabled": self.experiment_enabled,
            "active": self.active,
            "experimentId": self.experiment_id if self.active else None,
            "faultType": self.fault_type if self.active else None,
            "delayMs": self.delay_ms if self.active else None,
            "remainingMs": 120000 if self.active else None,
            "defaultDurationSeconds": 120,
            "maxDelayMs": 4500,
            "maxDurationSeconds": 600,
            "checkedAt": "2026-10-24T00:00:00Z",
        }

    def _diagnosis_detail(self, state: Dict[str, Any],
                          event_id: str) -> Dict[str, Any]:
        detail: Dict[str, Any] = {
            "id": "task-1",
            "faultEventId": event_id,
            "status": "RUNNING",
            "resultStatus": None,
            "failureReason": None,
            "report": {},
            "decisionTrace": [],
            "toolInvocations": [],
            "createdAt": "2026-10-24T00:00:00Z",
            "startedAt": "2026-10-24T00:00:01Z",
            "completedAt": None,
            "modelName": "test-model",
        }
        detail.update(state)
        detail.setdefault("id", "task-1")
        detail.setdefault("faultEventId", event_id)
        return detail

    def __call__(self, method: str, url: str,
                 payload: Optional[Dict[str, Any]]) -> HttpResponse:
        path = _path(url)
        if path == "/api/health":
            return _ok({
                "service": "fake",
                "version": "test",
                "status": "UP",
                "database": {"status": "UP"},
            })
        if path == "/api/experiments/faults" and method == "GET":
            return _ok(self.fault_status())
        if path == "/api/experiments/faults/enable" and method == "POST":
            if self.enable_failure_message:
                return _api_error(500, self.enable_failure_message)
            self.active = True
            self.experiment_id = "exp-1"
            self.fault_type = (payload or {}).get("faultType")
            self.delay_ms = (payload or {}).get("delayMs")
            return _ok(self.fault_status())
        if path == "/api/experiments/faults/disable" and method == "POST":
            if self.disable_failure_message:
                return _api_error(500, self.disable_failure_message)
            self.active = False
            return _ok(self.fault_status())
        if path == "/api/experiments/checkout" and method == "POST":
            return _ok(copy.deepcopy(self.execute_data))
        if path.startswith("/api/experiments/") and path.endswith("/execute"):
            if self.execute_exception is not None:
                raise self.execute_exception
            return _ok(copy.deepcopy(self.execute_data))
        if path.startswith("/api/fault-events/") and path.endswith("/diagnosis"):
            if not self.diagnosis_states:
                raise AssertionError("测试未配置诊断状态")
            event_id = path.split("/")[3]
            return _ok(self._diagnosis_detail(
                {"status": "RUNNING"}, event_id))
        if path.startswith("/api/diagnosis/tasks/"):
            if not self.diagnosis_states:
                raise AssertionError("测试未配置诊断轮询状态")
            self.diagnosis_poll_count += 1
            state = (self.diagnosis_states[0] if len(self.diagnosis_states) == 1
                     else self.diagnosis_states.pop(0))
            event_id = "event-fixture-1"
            return _ok(self._diagnosis_detail(state, event_id))
        if path.startswith("/api/fault-events/") and method == "GET":
            event_id = path.split("/")[3]
            return _ok({
                "event": {
                    "id": event_id,
                    "serviceName": "inventory-service",
                    "eventType": "DOWNSTREAM_HTTP_5XX",
                    "severity": "HIGH",
                    "status": "OPEN",
                    "occurredAt": "2026-10-24T00:00:00Z",
                    "requestId": "request-1",
                },
                "diagnosisTasks": [],
            })
        raise AssertionError("未预期的测试请求: %s %s" % (method, path))


class BlackBoxRunnerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.suite = baseline_suite()
        self.http_case = find_case(self.suite, "http500-baseline")
        self.delay_case = find_case(self.suite, "delay-slow-3500")
        self.normal_case = find_case(self.suite, "normal-baseline")
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)

    def _make_runner(self, handler, **overrides: Any):
        clock = FakeClock()
        client = FakeHttpClient(handler)
        fields: Dict[str, Any] = {
            "results_dir": Path(self.temp_dir.name) / "results",
            "run_id": "run-test",
            "repeats": 1,
            "max_diagnosis_runs": 2,
            "poll_interval_seconds": 2.0,
            "diagnosis_timeout_seconds": 30,
            "http_timeout_seconds": 5.0,
        }
        fields.update(overrides)
        config = RunConfig(**fields)
        runner = BlackBoxEvaluationRunner(
            config=config,
            raw_dir=Path(fields["results_dir"]) / fields["run_id"] / "raw",
            defaults=self.suite.defaults,
            client=client,
            sleep=clock.sleep,
            monotonic=clock.monotonic,
            now=lambda: datetime(2026, 10, 24, 12, 0, 0, tzinfo=timezone.utc),
        )
        return runner, client, clock

    def _read_raw(self, runner: BlackBoxEvaluationRunner,
                  sample_id: Optional[str]) -> Dict[str, Any]:
        self.assertIsNotNone(sample_id)
        path = runner.raw_dir / ("%s.json" % sample_id)
        self.assertTrue(path.is_file(), "原始样本未写入: %s" % path)
        return json.loads(path.read_text(encoding="utf-8"))

    def _http500_backend(self, **overrides: Any) -> FakeBackend:
        execute = {
            "experimentId": "exp-1",
            "presetFaultType": "HTTP_500",
            **http500_observation(),
        }
        execute.update(overrides.pop("execute_overrides", {}))
        return FakeBackend(execute, **overrides)

    def _delay_backend(self, **overrides: Any) -> FakeBackend:
        execute = {
            "experimentId": "exp-1",
            "presetFaultType": "DELAY",
            **delay_observation(),
        }
        return FakeBackend(execute, **overrides)

    # ---------------------------------------------------------------- 正常流程
    def test_http500_flow_enables_executes_diagnoses_and_cleans_up(self):
        backend = self._http500_backend(
            diagnosis_states=[_success_diagnosis_state()])
        runner, client, _ = self._make_runner(backend)

        sample = runner.collect(self.http_case, 1)

        self.assertIsNone(sample.harness_error)
        self.assertEqual("DOWNSTREAM_HTTP_5XX", sample.observation.outcome)
        self.assertTrue(sample.observation.fault_observed)
        self.assertEqual("event-fixture-1", sample.observation.fault_event_id)
        self.assertIsNotNone(sample.diagnosis)
        self.assertEqual("SUCCEEDED", sample.diagnosis.status)
        self.assertEqual("ROOT_CAUSE_IDENTIFIED",
                         sample.diagnosis.result_status)
        self.assertEqual(1, len(sample.diagnosis.tool_invocations))
        self.assertIn("inventory-service",
                      sample.diagnosis.report["rootCause"])

        enable_calls = [call for call in client.calls
                        if _path(call[1]).endswith("/enable")]
        self.assertEqual(1, len(enable_calls))
        self.assertEqual("HTTP_500", enable_calls[0][2]["faultType"])
        self.assertIsNone(enable_calls[0][2]["delayMs"])
        self.assertNotIn("http500", enable_calls[0][2]["note"].lower())
        self.assertNotIn("inventory", enable_calls[0][2]["note"].lower())

        raw = self._read_raw(runner, sample.sample_id)
        self.assertEqual("exp-1", raw["observation"]["experimentId"])
        self.assertEqual("exp-1", raw["faultInjection"]["experimentId"])
        self.assertEqual("HTTP_500", raw["faultInjection"]["faultType"])
        self.assertEqual("event-fixture-1", raw["faultEvent"]["id"])
        self.assertIn("decisionTrace", raw["diagnosis"])
        self.assertIn("toolInvocations", raw["diagnosis"])
        self.assertNotIn("agentInput", raw["diagnosis"])
        self.assertNotIn("expected", raw["observation"])
        self.assertFalse(backend.active)

    def test_delay_flow_uses_delay_injection_and_collects_slow_response(self):
        backend = self._delay_backend(
            diagnosis_states=[_success_diagnosis_state()])
        runner, client, _ = self._make_runner(backend)

        sample = runner.collect(self.delay_case, 1)

        self.assertIsNone(sample.harness_error)
        self.assertEqual("SLOW_RESPONSE", sample.observation.outcome)
        self.assertTrue(sample.observation.fault_observed)
        self.assertGreaterEqual(sample.observation.duration_ms or 0, 3000)
        enable_calls = [call for call in client.calls
                        if _path(call[1]).endswith("/enable")]
        self.assertEqual(1, len(enable_calls))
        self.assertEqual("DELAY", enable_calls[0][2]["faultType"])
        self.assertEqual(3500, enable_calls[0][2]["delayMs"])
        self.assertFalse(backend.active)
        self.assertIsNotNone(self._read_raw(runner, sample.sample_id))

    def test_normal_scenario_never_calls_agent_or_fault_enable(self):
        backend = FakeBackend({**normal_observation(), "orderStatus": "CREATED"})
        runner, client, _ = self._make_runner(backend)

        sample = runner.collect(self.normal_case, 1)

        self.assertIsNone(sample.harness_error)
        self.assertIsNone(sample.diagnosis)
        self.assertFalse(sample.observation.fault_observed)
        self.assertIsNone(sample.observation.fault_event_id)
        paths = client.paths()
        self.assertNotIn("/api/experiments/faults/enable", paths)
        self.assertNotIn("/api/experiments/faults/disable", paths)
        self.assertFalse(any("diagnosis" in path for path in paths))
        raw = self._read_raw(runner, sample.sample_id)
        self.assertIsNone(raw["diagnosis"])
        self.assertEqual("SUCCESS", raw["observation"]["outcome"])

    def test_run_suite_writes_raw_reports_and_manifest(self):
        backend = FakeBackend({**normal_observation(), "orderStatus": "CREATED"})
        runner, _, _ = self._make_runner(backend, max_diagnosis_runs=0)
        suite = CaseSuite(
            suite_id="unit-suite",
            label_version=self.suite.label_version,
            label_source="unit-test",
            defaults=self.suite.defaults,
            environment_requirements={},
            cases=(self.normal_case,),
        )
        config = RunConfig(
            results_dir=Path(self.temp_dir.name) / "results",
            run_id="run-test",
            repeats=1,
            max_diagnosis_runs=0,
            poll_interval_seconds=2.0,
            diagnosis_timeout_seconds=30,
        )

        result = run_suite(suite, runner, config, preflight={"backend": "UP"})

        self.assertFalse(result.aborted)
        self.assertEqual(1, len(result.samples))
        self.assertEqual(0, result.diagnosis_runs_started)
        self.assertTrue((runner.run_dir / "raw" / "run-test-normal-baseline-r1.json").is_file())
        self.assertTrue(result.report_paths["summaryJson"])
        self.assertTrue((runner.run_dir / "run-manifest.json").is_file())
        manifest = json.loads(
            (runner.run_dir / "run-manifest.json").read_text(encoding="utf-8"))
        self.assertEqual("black-box-http", manifest["mode"])
        self.assertFalse(manifest["directModelCalls"])
        self.assertEqual(0, manifest["diagnosisRunsStarted"])

    # ---------------------------------------------------------------- 环境错误
    def test_service_unavailable_fails_preflight(self):
        def handler(method: str, url: str, payload):
            if _path(url) == "/api/health":
                return _api_error(503, "backend unavailable")
            raise AssertionError("服务不可用时不应继续调用其他接口")

        runner, client, _ = self._make_runner(handler)
        with self.assertRaises(EnvironmentCheckError) as context:
            runner.preflight()
        self.assertIn("backend", str(context.exception))
        self.assertEqual(["/api/health"], client.paths())

    def test_fault_injection_disabled_fails_preflight_without_changing_config(self):
        backend = FakeBackend(normal_observation(), experiment_enabled=False)
        runner, client, _ = self._make_runner(backend)

        with self.assertRaises(EnvironmentCheckError) as context:
            runner.preflight()
        self.assertIn("未启用", str(context.exception))
        self.assertNotIn("/api/experiments/faults/enable", client.paths())

    def test_normal_cannot_confirm_fault_off_stops_before_checkout(self):
        def handler(method: str, url: str, payload):
            if _path(url) == "/api/experiments/faults":
                return _api_error(500, "fault status unavailable")
            raise AssertionError("NORMAL 前置检查失败后不应执行 checkout")

        runner, client, _ = self._make_runner(handler)
        sample = runner.collect(self.normal_case, 1)

        self.assertIsNotNone(sample.harness_error)
        raw = self._read_raw(runner, sample.sample_id)
        self.assertIn("environmentError", raw)
        self.assertNotIn("/api/experiments/checkout", client.paths())

    # ---------------------------------------------------------------- 故障错误
    def test_fault_injection_failure_is_saved_and_does_not_execute(self):
        backend = self._http500_backend(
            enable_failure_message="inventory rejected fault")
        runner, client, _ = self._make_runner(backend)

        sample = runner.collect(self.http_case, 1)

        self.assertIsNotNone(sample.harness_error)
        raw = self._read_raw(runner, sample.sample_id)
        self.assertIn("faultInjectionError", raw)
        self.assertIn("inventory rejected fault", raw["faultInjectionError"])
        self.assertNotIn("/api/experiments/exp-1/execute", client.paths())
        self.assertEqual({}, raw["observation"])

    def test_fault_cleanup_failure_is_recorded_separately(self):
        backend = self._http500_backend(
            diagnosis_states=[_success_diagnosis_state()],
            disable_failure_message="disable failed")
        runner, _, _ = self._make_runner(backend)

        sample = runner.collect(self.http_case, 1)

        self.assertIsNotNone(sample.harness_error)
        raw = self._read_raw(runner, sample.sample_id)
        self.assertIn("cleanupError", raw)
        self.assertIn("disable failed", raw["cleanupError"])
        self.assertEqual("SUCCEEDED", raw["diagnosis"]["status"])
        self.assertTrue(backend.active)

    # ---------------------------------------------------------------- 诊断错误
    def test_diagnosis_timeout_marks_harness_timeout(self):
        backend = self._http500_backend(
            diagnosis_states=[{"status": "RUNNING"}])
        runner, _, clock = self._make_runner(
            backend, diagnosis_timeout_seconds=10, poll_interval_seconds=2.0)

        sample = runner.collect(self.http_case, 1)

        self.assertIsNone(sample.harness_error)
        self.assertIsNotNone(sample.diagnosis)
        self.assertEqual("HARNESS_TIMEOUT", sample.diagnosis.status)
        self.assertIn("10 秒内", sample.diagnosis.failure_reason or "")
        self.assertEqual(5, backend.diagnosis_poll_count)
        self.assertTrue(any(abs(value - 2.0) < 0.001 for value in clock.sleeps))

    def test_agent_failure_is_preserved_as_terminal_task_failure(self):
        backend = self._http500_backend(
            diagnosis_states=[{
                "status": "FAILED",
                "resultStatus": None,
                "failureReason": "DeepSeek API error: upstream timeout",
            }])
        runner, _, _ = self._make_runner(backend)

        sample = runner.collect(self.http_case, 1)

        self.assertIsNone(sample.harness_error)
        self.assertIsNotNone(sample.diagnosis)
        self.assertEqual("FAILED", sample.diagnosis.status)
        self.assertIn("DeepSeek API error", sample.diagnosis.failure_reason or "")
        raw = self._read_raw(runner, sample.sample_id)
        self.assertEqual("FAILED", raw["diagnosis"]["status"])

    def test_unexpected_exception_mid_run_still_persists_raw_sample(self):
        backend = self._http500_backend(
            execute_exception=RuntimeError("unexpected execute failure"))
        runner, _, _ = self._make_runner(backend)

        sample = runner.collect(self.http_case, 1)

        self.assertIsNotNone(sample.harness_error)
        raw = self._read_raw(runner, sample.sample_id)
        self.assertIn("harnessError", raw)
        self.assertIn("RuntimeError", raw["harnessError"])
        self.assertEqual({}, raw["observation"])
        self.assertFalse(backend.active)

    # ---------------------------------------------------------------- 预算与 CLI
    def test_runtime_diagnosis_budget_stops_before_agent_call(self):
        backend = self._http500_backend(
            diagnosis_states=[_success_diagnosis_state()])
        runner, client, _ = self._make_runner(backend, max_diagnosis_runs=0)

        sample = runner.collect(self.http_case, 1)

        self.assertIsNotNone(sample.harness_error)
        raw = self._read_raw(runner, sample.sample_id)
        self.assertIn("budgetError", raw)
        self.assertNotIn(
            "/api/fault-events/event-fixture-1/diagnosis", client.paths())
        self.assertFalse(backend.active)

    def test_planned_budget_is_rejected_before_network(self):
        planned = planned_diagnosis_runs(self.suite, 2)
        self.assertEqual(4, planned)
        with self.assertRaises(BudgetExceededError):
            validate_run_budget(self.suite, 2, 2)

    def test_run_without_yes_is_rejected_without_creating_results(self):
        results_dir = Path(self.temp_dir.name) / "results-not-created"
        stderr = io.StringIO()
        with redirect_stderr(stderr), redirect_stdout(io.StringIO()):
            exit_code = run_eval.main([
                "run",
                "--results-dir", str(results_dir),
                "--repeats", "1",
            ])
        self.assertEqual(2, exit_code)
        self.assertIn("--yes", stderr.getvalue())
        self.assertFalse(results_dir.exists())


if __name__ == "__main__":
    unittest.main()
