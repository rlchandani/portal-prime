#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Exercise the offline CLI with recorded delivery evidence, not device mocks."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts/portal_delivery_summary.py"
RECEIPTS = [
    ROOT / "mocks/portal-face-image-admission-20261105/verification.json",
    ROOT / "mocks/portal-catalog-null-numeric-20261105/verification.json",
]


class DeliverySummaryTests(unittest.TestCase):
    def run_summary(self, evidence, existing=None):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "evidence.json"
            target = Path(directory) / "summary.md"
            raw = evidence if isinstance(evidence, str) else json.dumps(evidence)
            source.write_text(raw, encoding="utf-8")
            if existing is not None:
                target.write_bytes(existing)
            result = subprocess.run(
                [sys.executable, "-B", str(SCRIPT), str(source), "--output", str(target)],
                capture_output=True, text=True, timeout=10)
            self.assertEqual(source.read_text(encoding="utf-8"), raw)
            return result, target.read_bytes() if target.exists() else None

    def evidence(self):
        return json.loads(RECEIPTS[-1].read_text())

    def test_completed_receipts_render_exact_core_evidence_and_limits(self):
        for source in RECEIPTS:
            evidence = json.loads(source.read_text())
            with self.subTest(source=source.name):
                result, output = self.run_summary(evidence)
                self.assertEqual(result.returncode, 0, result.stderr)
                text = output.decode()
                self.assertIn(evidence["version"]["name"], text)
                self.assertIn(str(evidence["jvm"]["tests"]), text)
                self.assertIn(evidence["artifacts"]["performance"]["sha256"], text)
                self.assertIn(evidence["issueClock"]["originalStartUtc"], text)
                self.assertIn("Offline summary of supplied evidence", text)
                self.assertIn("fresh device check", text)
                if source == RECEIPTS[0]:
                    self.assertIn("Existing complete cached incorrect metadata", text)
                else:
                    self.assertIn("Native invalid text rejects versionCode first", text)
                self.assertIn("Living Area", text)

    def test_unknown_fields_are_excluded_and_text_cannot_create_html_or_links(self):
        evidence = self.evidence()
        evidence["privateLog"] = "PRIVATE_SENTINEL"
        evidence["delivery"]["living"]["result"]["privateToken"] = "PRIVATE_SENTINEL"
        evidence["scope"] = 'Photo <script>alert(1)</script>\n[click](https://invalid.example)'
        result, output = self.run_summary(evidence)
        self.assertEqual(result.returncode, 0, result.stderr)
        text = output.decode()
        self.assertNotIn("PRIVATE_SENTINEL", text)
        self.assertNotIn("<script>", text)
        self.assertNotIn("[click](https://invalid.example)", text)
        self.assertIn("&lt;script&gt;", text)

    def test_output_is_deterministic(self):
        first = self.run_summary(self.evidence())
        second = self.run_summary(self.evidence())
        self.assertEqual(first[0].returncode, 0, first[0].stderr)
        self.assertEqual(first[1], second[1])

    def test_selected_prose_cannot_open_fences_or_nested_lists(self):
        evidence = self.evidence()
        evidence["scope"] = "~~~"
        evidence["limits"] = ["---", "- nested list", "1. numbered list", "~~strike~~"]
        result, output = self.run_summary(evidence)
        self.assertEqual(result.returncode, 0, result.stderr)
        text = output.decode()
        self.assertNotIn("\n~~~\n", text)
        self.assertIn(r"\~\~\~", text)
        self.assertIn(r"\-\-\-", text)
        self.assertIn(r"1\. numbered list", text)
        self.assertIn(r"\~\~strike\~\~", text)

    def test_each_room_must_match_candidate_bytes_hash_version_and_readiness(self):
        for room in ("family", "living"):
            for field, value in (("bytes", 1), ("sha256", "0" * 64),
                                 ("serviceReady", False), ("serviceReady", "true")):
                evidence = self.evidence()
                installed = evidence["delivery"][room]
                if room == "living":
                    installed = installed["installed"]
                installed[field] = value
                with self.subTest(room=room, field=field, value=value):
                    self.assert_rejected(evidence)
            evidence = self.evidence()
            installed = evidence["delivery"][room]
            if room == "living":
                installed = installed["installed"]
            installed["app"]["versionCode"] += 1
            self.assert_rejected(evidence)

    def assert_rejected(self, evidence):
        result, output = self.run_summary(evidence)
        self.assertEqual(result.returncode, 2)
        self.assertIsNone(output)
        self.assertIn("invalid_delivery_evidence", result.stderr)

    def test_failed_missing_or_wrong_typed_gates_are_rejected(self):
        for field, value in (("schemaVersion", True), ("schemaVersion", 2),
                             ("status", "ready"), ("limits", []), ("limits", [""]),
                             ("version", {"code": True, "name": "1.193"})):
            evidence = self.evidence()
            evidence[field] = value
            with self.subTest(field=field, value=value):
                self.assert_rejected(evidence)
        for section, field, value in (
                ("build", "exitCode", 1), ("build", "seconds", float("nan")),
                ("jvm", "failures", 1), ("jvm", "errors", 1), ("jvm", "skipped", 1),
                ("jvm", "tests", 0), ("jvm", "tests", True),
                ("independentReview", "accepted", False)):
            evidence = self.evidence()
            evidence[section][field] = value
            with self.subTest(section=section, field=field):
                self.assert_rejected(evidence)
        evidence = self.evidence()
        del evidence["delivery"]["family"]["sha256"]
        self.assert_rejected(evidence)

    def test_existing_output_survives_success_or_validation_failure(self):
        for evidence in (self.evidence(), {"schemaVersion": 1}):
            result, output = self.run_summary(evidence, existing=b"keep this receipt\n")
            self.assertEqual(result.returncode, 2)
            self.assertEqual(output, b"keep this receipt\n")

    def test_write_failure_does_not_publish_a_partial_file(self):
        import portal_delivery_summary
        self.assertTrue(hasattr(portal_delivery_summary, "publish"))
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "summary.md"
            with self.assertRaises(UnicodeError):
                portal_delivery_summary.publish("prefix\ud800", target)
            self.assertFalse(target.exists())
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_invalid_capture_has_fixed_error_without_contents_or_path(self):
        for raw in ('{"PRIVATE_SENTINEL":', '{"schemaVersion":1,"schemaVersion":1}', '[]'):
            result, output = self.run_summary(raw)
            self.assertEqual(result.returncode, 2)
            self.assertIsNone(output)
            self.assertIn("invalid_delivery_evidence", result.stderr)
            self.assertNotIn("PRIVATE_SENTINEL", result.stderr + result.stdout)
            self.assertNotIn("evidence.json", result.stderr + result.stdout)

    def test_clock_order_and_missing_timezone_are_rejected(self):
        for field, value in (("originalStartUtc", "2026-11-06T02:29:50Z"),
                             ("targetUtc", "2026-11-05T02:29:50Z"),
                             ("hardLimitUtc", "2026-11-05T02:59:50")):
            evidence = copy.deepcopy(self.evidence())
            evidence["issueClock"][field] = value
            with self.subTest(field=field):
                self.assert_rejected(evidence)

    def captured_evidence(self):
        return json.loads(
            (ROOT / "mocks/portal-daily-content-signed-day-20261105/verification.json").read_text())

    def assert_capture_timing(self, recorded, elapsed, target, hard):
        evidence = self.captured_evidence()
        evidence["recordedUtc"] = recorded
        result, output = self.run_summary(evidence)
        self.assertEqual(result.returncode, 0, result.stderr)
        text = output.decode()
        self.assertIn(f"Elapsed at evidence capture: {elapsed} minutes.", text)
        self.assertIn(
            f"Time limits at evidence capture: target {target}; hard limit {hard}.", text)
        self.assertIn("recorded time is not a completion timestamp", text)

    def test_recorded_delivery_includes_original_elapsed_capture_time(self):
        self.assert_capture_timing(
            "2026-11-05T04:24:21.779347Z", "12.18", "within", "within")

    def test_capture_exactly_at_target_remains_within_target(self):
        self.assert_capture_timing("2026-11-05T04:27:11Z", "15.00", "within", "within")

    def test_capture_past_target_is_not_hidden_by_display_rounding(self):
        self.assert_capture_timing(
            "2026-11-05T04:27:11.000001Z", "15.00", "past", "within")

    def test_capture_exactly_at_hard_limit_remains_within_hard_limit(self):
        self.assert_capture_timing("2026-11-05T04:42:11Z", "30.00", "past", "within")

    def test_capture_past_hard_limit_is_reported_without_rewriting_delivery(self):
        self.assert_capture_timing(
            "2026-11-05T04:42:11.000001Z", "30.00", "past", "past")

    def test_equivalent_offset_timestamp_retains_capture_elapsed_and_limits(self):
        self.assert_capture_timing(
            "2026-11-04T20:24:21.779347-08:00", "12.18", "within", "within")


if __name__ == "__main__":
    unittest.main()
