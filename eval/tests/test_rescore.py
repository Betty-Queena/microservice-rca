"""离线重判集成测试（不调用任何服务）。"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parents[1]
if str(EVAL_DIR) not in sys.path:
    sys.path.insert(0, str(EVAL_DIR))

from rca_eval.cases import load_raw_samples, load_suite
from rca_eval.report import write_reports
from rca_eval.scoring import score_all
from tests.fixtures import (
    SUITE_PATH,
    diagnosis_payload,
    http500_observation,
    normal_observation,
)


class RescoreTest(unittest.TestCase):
    def test_rescore_existing_raw_results_without_model_calls(self):
        suite = load_suite(SUITE_PATH)
        with tempfile.TemporaryDirectory() as tmp:
            raw_dir = Path(tmp) / "raw"
            raw_dir.mkdir()
            normal = {
                "schemaVersion": "1.0",
                "sampleId": "normal-r1",
                "caseId": "normal-baseline",
                "repeat": 1,
                "observation": normal_observation(),
                "diagnosis": None,
            }
            fault = {
                "schemaVersion": "1.0",
                "sampleId": "http500-r1",
                "caseId": "http500-baseline",
                "repeat": 1,
                "observation": http500_observation(),
                "diagnosis": diagnosis_payload(),
            }
            (raw_dir / "normal-r1.json").write_text(
                json.dumps(normal, ensure_ascii=False), encoding="utf-8")
            (raw_dir / "http500-r1.json").write_text(
                json.dumps(fault, ensure_ascii=False), encoding="utf-8")

            samples = load_raw_samples(tmp)
            self.assertEqual(2, len(samples))
            results = score_all(suite, samples)
            output = Path(tmp) / "out"
            paths = write_reports(
                output, suite, results, manifest_extra={"inputPath": str(tmp)})

            with Path(paths["summaryJson"]).open("r", encoding="utf-8") as handle:
                report = json.load(handle)
            self.assertEqual(2, report["summary"]["totalSamples"])
            self.assertEqual(1, report["summary"]["normalPass"])
            self.assertEqual(1, report["summary"]["rootCauseIdentified"])
            self.assertEqual(1, report["summary"]["eligibleDiagnosisSamples"])
            self.assertEqual(1.0, report["summary"]["strictAccuracy"])
            self.assertFalse(report["manifest"]["networkCalls"])
            self.assertFalse(report["manifest"]["modelCalls"])


if __name__ == "__main__":
    unittest.main()
