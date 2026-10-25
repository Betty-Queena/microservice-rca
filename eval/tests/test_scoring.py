"""离线判分规则测试（仅标准库 unittest）。"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parents[1]
if str(EVAL_DIR) not in sys.path:
    sys.path.insert(0, str(EVAL_DIR))

from rca_eval.scoring import score_all, score_sample
from tests.fixtures import (
    baseline_suite,
    delay_observation,
    diagnosis_payload,
    find_case,
    http500_observation,
    make_sample,
    normal_observation,
)


class ScoringTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.suite = baseline_suite()
        cls.http_case = find_case(cls.suite, "http500-baseline")
        cls.delay_case = find_case(cls.suite, "delay-slow-3500")
        cls.normal_case = find_case(cls.suite, "normal-baseline")

    def score_http(self, diagnosis=None, observation=None, **sample_overrides):
        sample = make_sample(
            "http500-baseline",
            observation or http500_observation(),
            diagnosis if diagnosis is not None else diagnosis_payload(),
            **sample_overrides,
        )
        return score_sample(self.http_case, sample, self.suite.label_version)

    def test_correct_http500_report_is_strictly_correct(self):
        result = self.score_http()
        self.assertEqual("ROOT_CAUSE_IDENTIFIED", result.sample_status)
        self.assertTrue(result.eligible)
        self.assertTrue(result.observation_passed)
        self.assertTrue(result.service_correct)
        self.assertTrue(result.category_correct)
        self.assertEqual(1.0, result.evidence_coverage)
        self.assertTrue(result.strict_correct)
        self.assertEqual((), result.missing_fields)

    def test_wrong_service_is_not_strict_and_flags_conflict(self):
        diagnosis = diagnosis_payload()
        diagnosis["report"]["rootCause"] = "并非库存服务，而是 order-service 返回 HTTP 500"
        diagnosis["report"]["hypothesis"] = "不是库存服务的问题"
        result = self.score_http(diagnosis=diagnosis)
        self.assertFalse(result.service_correct)
        self.assertTrue(result.category_correct)
        self.assertFalse(result.strict_correct)
        self.assertTrue(any(flag.startswith("SERVICE_CONFLICT:") for flag in result.risk_flags))
        self.assertTrue(any(flag.startswith("NEGATED_SERVICE_ALIAS:")
                            for flag in result.risk_flags))

    def test_negated_category_keyword_is_not_counted(self):
        diagnosis = diagnosis_payload()
        diagnosis["report"]["rootCause"] = "库存服务未出现 HTTP 500"
        diagnosis["report"]["hypothesis"] = "无 500 错误"
        result = self.score_http(diagnosis=diagnosis)
        self.assertFalse(result.category_correct)
        self.assertFalse(result.strict_correct)
        self.assertIn("NEGATED_CATEGORY_KEYWORD:HTTP 500", result.risk_flags)

    def test_delay_risk_keyword_is_flagged(self):
        diagnosis = diagnosis_payload()
        diagnosis["report"]["rootCause"] = "inventory-service 响应耗时约 3500ms"
        diagnosis["report"]["hypothesis"] = "发生超时"
        sample = make_sample("delay-slow-3500", delay_observation(), diagnosis)
        result = score_sample(self.delay_case, sample, self.suite.label_version)
        self.assertTrue(result.service_correct)
        self.assertTrue(result.category_correct)
        self.assertIn("RISK_KEYWORD:超时", result.risk_flags)
        self.assertTrue(result.strict_correct)

    def test_negated_risk_keyword_is_not_flagged(self):
        diagnosis = diagnosis_payload()
        diagnosis["report"]["rootCause"] = "inventory-service 响应耗时约 3500ms"
        diagnosis["report"]["hypothesis"] = "未发生超时"
        sample = make_sample("delay-slow-3500", delay_observation(), diagnosis)
        result = score_sample(self.delay_case, sample, self.suite.label_version)
        self.assertNotIn("RISK_KEYWORD:超时", result.risk_flags)
        self.assertTrue(any("否定" in note for note in result.notes))

    def test_insufficient_evidence_is_eligible_but_not_strict(self):
        diagnosis = diagnosis_payload(
            resultStatus="INSUFFICIENT_EVIDENCE",
            report={"reason": "证据不足，无法定位根因"},
        )
        result = self.score_http(diagnosis=diagnosis)
        self.assertEqual("INSUFFICIENT_EVIDENCE", result.sample_status)
        self.assertTrue(result.eligible)
        self.assertFalse(result.strict_correct)
        self.assertEqual((), result.missing_fields)

    def test_failed_timeout_is_classified(self):
        diagnosis = diagnosis_payload(
            status="FAILED", resultStatus=None, failureReason="整体预算超时")
        result = self.score_http(diagnosis=diagnosis)
        self.assertEqual("FAILED", result.sample_status)
        self.assertTrue(result.eligible)
        self.assertEqual("BUDGET_TIMEOUT", result.failure_category)
        self.assertFalse(result.strict_correct)

    def test_missing_report_fields_are_explicit(self):
        diagnosis = diagnosis_payload()
        del diagnosis["report"]["hypothesis"]
        del diagnosis["report"]["affectedServices"]
        result = self.score_http(diagnosis=diagnosis)
        self.assertIn("report.hypothesis", result.missing_fields)
        self.assertIn("report.affectedServices", result.missing_fields)
        self.assertFalse(result.strict_correct)

    def test_missing_evidence_is_flagged(self):
        diagnosis = diagnosis_payload()
        del diagnosis["report"]["supportingEvidenceIds"]
        result = self.score_http(diagnosis=diagnosis)
        self.assertEqual(0.0, result.evidence_coverage)
        self.assertIn("report.supportingEvidenceIds", result.missing_fields)
        self.assertIn("NO_SUPPORTING_EVIDENCE", result.risk_flags)
        self.assertFalse(result.strict_correct)

    def test_service_can_be_found_in_affected_services(self):
        diagnosis = diagnosis_payload()
        diagnosis["report"]["rootCause"] = "下游返回错误"
        diagnosis["report"]["hypothesis"] = "上游收到 HTTP 500 响应"
        diagnosis["report"]["affectedServices"] = ["inventory-service"]
        result = self.score_http(diagnosis=diagnosis)
        self.assertTrue(result.service_correct)
        self.assertTrue(result.category_correct)
        self.assertTrue(result.strict_correct)

    def test_numeric_keyword_does_not_match_inside_larger_number(self):
        diagnosis = diagnosis_payload()
        diagnosis["report"]["rootCause"] = "inventory-service 耗时 3500ms"
        diagnosis["report"]["hypothesis"] = "未发现 500 错误"
        result = self.score_http(diagnosis=diagnosis)
        self.assertFalse(result.category_correct)

    def test_normal_pass_is_not_eligible(self):
        sample = make_sample("normal-baseline", normal_observation(), None)
        result = score_sample(self.normal_case, sample, self.suite.label_version)
        self.assertEqual("NORMAL_PASS", result.sample_status)
        self.assertFalse(result.eligible)
        self.assertFalse(result.strict_correct)

    def test_normal_pass_agent_metrics_are_not_applicable(self):
        sample = make_sample("normal-baseline", normal_observation(), None)
        result = score_sample(self.normal_case, sample, self.suite.label_version)
        self.assertEqual("NORMAL_PASS", result.sample_status)
        self.assertIsNone(result.metrics.model_call_count)
        self.assertIsNone(result.metrics.total_tokens)
        self.assertIsNone(result.metrics.wall_clock_ms)
        self.assertEqual((), result.metrics.incomplete_reasons)

    def test_normal_event_is_false_alarm(self):
        sample = make_sample(
            "normal-baseline", normal_observation(faultEventId="event-unexpected"), None)
        result = score_sample(self.normal_case, sample, self.suite.label_version)
        self.assertEqual("FALSE_ALARM", result.sample_status)
        self.assertIn("FALSE_ALARM", result.risk_flags)

    def test_observation_mismatch_is_excluded(self):
        sample = make_sample(
            "http500-baseline",
            http500_observation(outcome="SLOW_RESPONSE", httpStatus=200),
            diagnosis_payload(),
        )
        result = score_sample(self.http_case, sample, self.suite.label_version)
        self.assertEqual("OBSERVATION_MISMATCH", result.sample_status)
        self.assertFalse(result.eligible)

    def test_missing_diagnosis_is_invalid_sample(self):
        sample = make_sample("http500-baseline", http500_observation(), None)
        result = score_sample(self.http_case, sample, self.suite.label_version)
        self.assertEqual("INVALID_SAMPLE", result.sample_status)
        self.assertIn("diagnosis", result.missing_fields)
        self.assertFalse(result.eligible)

    def test_harness_error_is_invalid_environment(self):
        sample = make_sample(
            "http500-baseline", http500_observation(), diagnosis_payload(),
            harnessError="backend not reachable")
        result = score_sample(self.http_case, sample, self.suite.label_version)
        self.assertEqual("INVALID_ENV", result.sample_status)
        self.assertEqual("HARNESS_ERROR", result.failure_category)
        self.assertFalse(result.eligible)

    def test_unknown_case_is_recorded_but_excluded(self):
        sample = make_sample("missing-case", normal_observation(), None)
        results = score_all(self.suite, [sample])
        self.assertEqual("UNKNOWN_CASE", results[0].sample_status)
        self.assertFalse(results[0].eligible)

    def test_metrics_are_extracted_from_run_summary(self):
        result = self.score_http()
        self.assertEqual(1, result.metrics.model_call_count)
        self.assertEqual(1, result.metrics.tool_execution_count)
        self.assertEqual(0, result.metrics.tool_failure_count)
        self.assertEqual(100, result.metrics.prompt_tokens)
        self.assertEqual(20, result.metrics.completion_tokens)
        self.assertEqual(120, result.metrics.total_tokens)
        self.assertEqual(18000, result.metrics.wall_clock_ms)
        self.assertEqual(17000, result.metrics.execution_ms)
        self.assertTrue(result.metrics.usage_available)

    def test_tool_failure_is_counted(self):
        diagnosis = diagnosis_payload(toolInvocations=[
            {"toolName": "query_logs", "status": "FAILED", "latencyMs": 50},
            {"toolName": "query_metrics", "status": "SUCCESS", "latencyMs": 70},
        ])
        result = self.score_http(diagnosis=diagnosis)
        self.assertEqual(2, result.metrics.tool_execution_count)
        self.assertEqual(1, result.metrics.tool_failure_count)
        self.assertEqual(120, result.metrics.tool_latency_ms)

    def test_missing_metrics_are_null_not_fabricated(self):
        diagnosis = diagnosis_payload(decisionTrace=[], toolInvocations=[])
        result = self.score_http(diagnosis=diagnosis)
        self.assertIsNone(result.metrics.model_call_count)
        self.assertIsNone(result.metrics.total_tokens)
        self.assertFalse(result.metrics.usage_available)
        self.assertTrue(result.metrics.incomplete_reasons)


if __name__ == "__main__":
    unittest.main()
