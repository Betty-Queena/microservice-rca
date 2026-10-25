"""case 校验与原始结果读取测试（仅标准库 unittest）。"""

from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parents[1]
if str(EVAL_DIR) not in sys.path:
    sys.path.insert(0, str(EVAL_DIR))

from rca_eval.cases import load_raw_samples, load_suite, suite_from_dict
from rca_eval.models import CaseValidationError, RawSample
from tests.fixtures import SUITE_PATH, baseline_suite


def _raw_suite_data():
    with SUITE_PATH.open("r", encoding="utf-8") as handle:
        return json.load(handle)


class CaseLoadingTest(unittest.TestCase):
    def test_baseline_suite_loads_with_three_cases(self):
        suite = baseline_suite()
        self.assertEqual("day7-baseline", suite.suite_id)
        self.assertEqual(3, len(suite.cases))
        self.assertEqual(
            ["http500-baseline", "delay-slow-3500", "normal-baseline"],
            [case.case_id for case in suite.cases])
        self.assertTrue(suite.cases[0].diagnosis)
        self.assertFalse(suite.cases[2].diagnosis)
        self.assertIsNone(suite.cases[2].injection)

    def test_duplicate_case_id_is_rejected(self):
        data = _raw_suite_data()
        data["cases"].append(copy.deepcopy(data["cases"][0]))
        with self.assertRaises(CaseValidationError) as raised:
            suite_from_dict(data)
        self.assertTrue(any("重复" in error for error in raised.exception.errors))

    def test_normal_case_cannot_enable_diagnosis(self):
        data = _raw_suite_data()
        normal = data["cases"][2]
        normal["diagnosis"] = True
        with self.assertRaises(CaseValidationError) as raised:
            suite_from_dict(data)
        self.assertTrue(any("正常场景不能启用" in error for error in raised.exception.errors))

    def test_diagnosis_case_requires_judge(self):
        data = _raw_suite_data()
        data["cases"][0]["judge"] = None
        with self.assertRaises(CaseValidationError) as raised:
            suite_from_dict(data)
        self.assertTrue(any("缺少 judge" in error for error in raised.exception.errors))

    def test_min_evidence_matchers_must_be_in_range(self):
        data = _raw_suite_data()
        data["cases"][0]["judge"]["minEvidenceMatchers"] = 99
        with self.assertRaises(CaseValidationError) as raised:
            suite_from_dict(data)
        self.assertTrue(any("minEvidenceMatchers" in error for error in raised.exception.errors))

    def test_unknown_scenario_is_rejected(self):
        data = _raw_suite_data()
        data["cases"][0]["scenario"] = "UNKNOWN"
        with self.assertRaises(CaseValidationError) as raised:
            suite_from_dict(data)
        self.assertTrue(any("scenario" in error for error in raised.exception.errors))

    def test_zero_repeat_is_not_silently_replaced(self):
        data = _raw_suite_data()
        data["defaults"]["repeat"] = 0
        with self.assertRaises(CaseValidationError) as raised:
            suite_from_dict(data)
        self.assertTrue(any("repeat" in error for error in raised.exception.errors))


class RawSampleLoadingTest(unittest.TestCase):
    def test_missing_observation_is_tolerated_and_marked(self):
        sample = RawSample.from_dict({"sampleId": "x", "caseId": "http500-baseline"})
        self.assertFalse(sample.observation.present)
        self.assertIsNone(sample.diagnosis)
        self.assertTrue(sample.observation.parse_warnings)

    def test_load_raw_samples_from_directory_uses_filename_as_fallback_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            raw_dir = Path(tmp) / "raw"
            raw_dir.mkdir()
            (raw_dir / "sample-1.json").write_text(
                json.dumps({
                    "caseId": "normal-baseline",
                    "repeat": 1,
                    "observation": {
                        "faultObserved": False,
                        "outcome": "SUCCESS",
                        "httpStatus": 200,
                    },
                    "diagnosis": None,
                }),
                encoding="utf-8",
            )
            samples = load_raw_samples(tmp)
            self.assertEqual(1, len(samples))
            self.assertEqual("sample-1", samples[0].sample_id)
            self.assertEqual("normal-baseline", samples[0].case_id)

    def test_load_raw_samples_from_jsonl(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "samples.jsonl"
            path.write_text(
                json.dumps({"sampleId": "a", "caseId": "normal-baseline",
                            "observation": {}}) + "\n" +
                json.dumps({"sampleId": "b", "caseId": "http500-baseline",
                            "observation": {}}) + "\n",
                encoding="utf-8",
            )
            samples = load_raw_samples(path)
            self.assertEqual(["a", "b"], [sample.sample_id for sample in samples])


if __name__ == "__main__":
    unittest.main()
