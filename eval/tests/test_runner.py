"""后续黑盒 runner 接口约定测试。"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parents[1]
if str(EVAL_DIR) not in sys.path:
    sys.path.insert(0, str(EVAL_DIR))

from rca_eval.runner import (
    BlackBoxRunnerNotImplemented,
    PlaceholderEvaluationRunner,
    raw_sample_contract,
)
from tests.fixtures import baseline_suite, find_case


class RunnerContractTest(unittest.TestCase):
    def test_contract_lists_required_fields(self):
        contract = raw_sample_contract()
        self.assertEqual("1.0", contract["schemaVersion"])
        for field in ("sampleId", "caseId", "repeat", "observation", "diagnosis"):
            self.assertIn(field, contract["requiredFields"])
        self.assertIn("groundTruthRule", contract)

    def test_placeholder_runner_refuses_to_fake_results(self):
        case = find_case(baseline_suite(), "http500-baseline")
        with self.assertRaises(BlackBoxRunnerNotImplemented):
            PlaceholderEvaluationRunner().collect(case, 1)


if __name__ == "__main__":
    unittest.main()
