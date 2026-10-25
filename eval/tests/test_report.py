"""JSON / CSV 报告生成测试（仅标准库 unittest）。"""

from __future__ import annotations

import csv
import json
import sys
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parents[1]
if str(EVAL_DIR) not in sys.path:
    sys.path.insert(0, str(EVAL_DIR))

from rca_eval.models import Metrics
from rca_eval.report import build_report, summary_stats, write_reports
from rca_eval.scoring import score_all
from tests.fixtures import (
    baseline_suite,
    diagnosis_payload,
    http500_observation,
    make_sample,
    normal_observation,
)


class ReportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.suite = baseline_suite()
        normal = make_sample("normal-baseline", normal_observation(), None)
        correct = make_sample(
            "http500-baseline", http500_observation(), diagnosis_payload())
        failed_diagnosis = diagnosis_payload(
            status="FAILED", resultStatus=None, failureReason="整体预算超时")
        failed = make_sample(
            "http500-baseline", http500_observation(faultEventId="event-fixture-3"),
            failed_diagnosis, repeat=2)
        cls.results = score_all(cls.suite, [normal, correct, failed])

    def test_summary_counts_and_denominator(self):
        summary = summary_stats(self.results)
        self.assertEqual(3, summary["totalSamples"])
        self.assertEqual(1, summary["normalSamples"])
        self.assertEqual(2, summary["diagnosisSamples"])
        self.assertEqual(2, summary["eligibleDiagnosisSamples"])
        self.assertEqual(1, summary["normalPass"])
        self.assertEqual(0, summary["falseAlarms"])
        self.assertEqual(1, summary["rootCauseIdentified"])
        self.assertEqual(1, summary["failed"])
        self.assertEqual(0.5, summary["strictAccuracy"])
        self.assertEqual(0.5, summary["serviceAccuracy"])
        self.assertEqual(0.5, summary["categoryAccuracy"])

    def test_normal_pass_is_not_review_candidate_or_incomplete_metric(self):
        report = build_report(self.suite, self.results)

        normal_result = next(
            result for result in self.results
            if result.sample_id == "fixture-normal-baseline-r1")
        self.assertFalse(normal_result.strict_correct)

        review_ids = [item["sampleId"] for item in report["reviewCandidates"]]
        self.assertNotIn("fixture-normal-baseline-r1", review_ids)
        self.assertEqual(
            0, report["summary"]["dataQuality"]["samplesWithIncompleteMetrics"])

        normal_case = next(
            case for case in report["cases"] if case["caseId"] == "normal-baseline")
        self.assertEqual(
            0, normal_case["dataQuality"]["samplesWithIncompleteMetrics"])

    def test_normal_agent_metric_gap_never_enters_review(self):
        normal_result = next(
            result for result in self.results
            if result.sample_id == "fixture-normal-baseline-r1")
        synthetic = replace(
            normal_result,
            metrics=Metrics(incomplete_reasons=("合成指标缺失",)))
        report = build_report(self.suite, [synthetic])

        self.assertEqual([], report["reviewCandidates"])
        self.assertEqual(
            0, report["summary"]["dataQuality"]["samplesWithIncompleteMetrics"])

    def test_false_alarm_normal_remains_reviewable(self):
        false_alarm = make_sample(
            "normal-baseline",
            normal_observation(faultEventId="event-unexpected"),
            None)
        results = score_all(self.suite, [false_alarm])
        report = build_report(self.suite, results)

        self.assertEqual("FALSE_ALARM", report["failures"][0]["sampleStatus"])
        review_ids = [item["sampleId"] for item in report["reviewCandidates"]]
        self.assertIn(false_alarm.sample_id, review_ids)
        # Agent 指标仍属于不适用，不因异常样本重新计入指标缺失。
        self.assertEqual(
            0, report["summary"]["dataQuality"]["samplesWithIncompleteMetrics"])

    def test_reports_are_written_with_expected_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            paths = write_reports(
                tmp, self.suite, self.results,
                manifest_extra={"inputPath": "fixture-dir"})
            for key in ("summaryJson", "manifestJson", "samplesJsonl",
                        "summaryCsv", "failuresMarkdown"):
                self.assertTrue(Path(paths[key]).is_file(), key)

            with Path(paths["summaryJson"]).open("r", encoding="utf-8") as handle:
                report = json.load(handle)
            self.assertEqual("offline-rescore", report["manifest"]["mode"])
            self.assertFalse(report["manifest"]["networkCalls"])
            self.assertFalse(report["manifest"]["modelCalls"])
            self.assertEqual(3, report["summary"]["totalSamples"])
            self.assertEqual("fixture-dir", report["manifest"]["inputPath"])
            self.assertTrue(report["failures"])

            with Path(paths["summaryCsv"]).open(
                    "r", encoding="utf-8-sig", newline="") as handle:
                rows = list(csv.DictReader(handle))
            self.assertEqual(3, len(rows))
            self.assertIn("suiteId", rows[0])
            self.assertIn("strictCorrect", rows[0])

            with Path(paths["samplesJsonl"]).open("r", encoding="utf-8") as handle:
                self.assertEqual(3, len([line for line in handle if line.strip()]))

            failures_text = Path(paths["failuresMarkdown"]).read_text(encoding="utf-8")
            self.assertIn("FAILED", failures_text)


if __name__ == "__main__":
    unittest.main()
