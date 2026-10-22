# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

MODULE_PATH = Path(__file__).with_name("aiv_aidlc_sensor.py")
SPEC = importlib.util.spec_from_file_location("aiv_aidlc_sensor", MODULE_PATH)
sensor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sensor)


class SensorTest(unittest.TestCase):
    def test_file_request_contains_real_content(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "A.java"
            path.write_text("class A {}", encoding="utf-8")
            request = json.loads(sensor.build_file_request(str(path)))
            self.assertEqual("class A {}", request["artifact"]["files"][0]["content"])

    def test_advisory_integration_failure_is_visible_but_passes(self):
        result = sensor.integration_result("jar missing", False)
        self.assertTrue(result["pass"])
        self.assertEqual("aiv.integration.unavailable", result["violations"][0]["rule"])

    def test_blocking_integration_failure_fails_closed(self):
        result = sensor.integration_result("jar missing", True)
        self.assertFalse(result["pass"])

    def test_maps_findings_and_gate_only_failure(self):
        verdict = {"passed": False, "blocking": True, "gates": [
            {"id": "security", "passed": False, "blocks_ci": True, "message": "secret", "findings": [
                {"file": "A.java", "start_line": 2, "rule_id": "security.key", "message": "key"}
            ]},
            {"id": "design", "passed": False, "blocks_ci": False, "message": "design review", "findings": []}
        ]}
        result = sensor.to_sensor_result(verdict, True)
        self.assertFalse(result["pass"])
        self.assertEqual(2, len(result["violations"]))
        self.assertEqual("design.gate", result["violations"][1]["rule"])
        self.assertTrue(sensor.to_sensor_result(verdict, False)["pass"])

    def test_blocking_rejects_declared_external_inputs(self):
        verdict = {"passed": True, "blocking": False, "gates": [],
                   "external_inputs": ["decision-model"]}
        result = sensor.to_sensor_result(verdict, True)
        self.assertFalse(result["pass"])
        self.assertEqual("aiv.integration.external-input", result["violations"][0]["rule"])
        self.assertTrue(sensor.to_sensor_result(verdict, False)["pass"])

    def test_blocking_main_requires_trusted_policy_ref(self):
        output = io.StringIO()
        with patch.object(sys, "argv", ["sensor", "--file", "x.java", "--blocking"]), \
                patch.dict(os.environ, {}, clear=True), redirect_stdout(output):
            self.assertEqual(1, sensor.main())
        result = json.loads(output.getvalue())
        self.assertFalse(result["pass"])
        self.assertIn("requires", result["violations"][0]["message"])

    @patch.object(sensor.subprocess, "run")
    def test_run_verify_accepts_gate_exit_and_requires_trusted_policy(self, run):
        run.return_value.returncode = 1
        run.return_value.stdout = '{"passed":false,"blocking":true,"gates":[]}'
        run.return_value.stderr = ""
        result = sensor.run_verify("aiv.jar", "{}", ".", "origin/main", True)
        self.assertFalse(result["passed"])
        self.assertIn("--require-trusted-policy", run.call_args.args[0])

    @patch.object(sensor.subprocess, "run")
    def test_run_verify_rejects_empty_or_error_output(self, run):
        run.return_value.returncode = 2
        run.return_value.stdout = ""
        run.return_value.stderr = "bad"
        with self.assertRaises(RuntimeError):
            sensor.run_verify("aiv.jar", "{}", ".", None, False)
        run.return_value.stdout = '{"error":"bad request"}'
        with self.assertRaises(RuntimeError):
            sensor.run_verify("aiv.jar", "{}", ".", None, False)


if __name__ == "__main__":
    unittest.main()
