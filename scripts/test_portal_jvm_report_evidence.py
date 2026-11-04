# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Actual readiness CLI evidence checks with literal generated JUnit reports."""
import unittest

from test_portal_release_readiness import PASSING_JVM_REPORT, ReadinessFixture
import test_portal_release_readiness_edges as readiness_edges


class JvmReportEvidenceTest(unittest.TestCase):
    gradle_body = staticmethod(readiness_edges.ReleaseReadinessEdgesTest.gradle_body)
    set_jvm_report = readiness_edges.ReleaseReadinessEdgesTest.set_jvm_report
    assert_failed = readiness_edges.ReleaseReadinessEdgesTest.assert_failed

    def setUp(self):
        self.fixture = ReadinessFixture()
        self.addCleanup(self.fixture.close)
        self.assertIn(PASSING_JVM_REPORT, self.gradle_body(self.fixture))

    def assert_rejected_report(self, report):
        self.set_jvm_report(self.fixture, report)
        self.assert_failed(self.fixture, self.fixture.run(), r"jvm|report|test|case")

    def test_nonzero_header_without_any_testcases_cannot_report_ready(self):
        self.assert_rejected_report(
            '<testsuite tests="3" failures="0" errors="0" skipped="0"/>')

    def test_three_test_header_with_only_one_passing_case_cannot_report_ready(self):
        self.assert_rejected_report(
            '<testsuite tests="3" failures="0" errors="0" skipped="0">'
            '<testcase classname="Fixture" name="one"/></testsuite>')

    def test_one_test_header_with_three_passing_cases_cannot_report_ready(self):
        self.assert_rejected_report(
            '<testsuite tests="1" failures="0" errors="0" skipped="0">'
            '<testcase classname="Fixture" name="one"/>'
            '<testcase classname="Fixture" name="two"/>'
            '<testcase classname="Fixture" name="three"/></testsuite>')

    def test_clean_header_cannot_hide_case_failure(self):
        self.assert_rejected_report(
            '<testsuite tests="1" failures="0" errors="0" skipped="0">'
            '<testcase classname="Fixture" name="failed">'
            '<failure message="literal failed assertion"/></testcase></testsuite>')

    def test_clean_header_cannot_hide_case_error(self):
        self.assert_rejected_report(
            '<testsuite tests="1" failures="0" errors="0" skipped="0">'
            '<testcase classname="Fixture" name="errored">'
            '<error message="literal setup error"/></testcase></testsuite>')

    def test_clean_header_cannot_hide_skipped_case(self):
        self.assert_rejected_report(
            '<testsuite tests="1" failures="0" errors="0" skipped="0">'
            '<testcase classname="Fixture" name="skipped">'
            '<skipped message="literal skipped case"/></testcase></testsuite>')

    def test_complete_three_passing_cases_report_ready(self):
        self.set_jvm_report(
            self.fixture,
            '<testsuite tests="3" failures="0" errors="0" skipped="0">'
            '<testcase classname="Fixture" name="one"/>'
            '<testcase classname="Fixture" name="two"/>'
            '<testcase classname="Fixture" name="three"/></testsuite>')
        result = self.fixture.run()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        receipt = self.fixture.receipt()
        self.assertEqual("ready", receipt["status"])
        self.assertEqual(
            {"tests": 3, "failures": 0, "errors": 0, "skipped": 0, "suites": 1},
            receipt["jvm"])
        self.assertFalse(receipt["published"])
        self.assertFalse(receipt["deployed"])
        self.assertTrue((self.fixture.output / "candidate.apk").is_file())

    def test_complete_cases_allow_additional_zero_test_empty_suite(self):
        self.set_jvm_report(
            self.fixture,
            '<testsuite tests="3" failures="0" errors="0" skipped="0">'
            '<testcase classname="Fixture" name="one"/>'
            '<testcase classname="Fixture" name="two"/>'
            '<testcase classname="Fixture" name="three"/></testsuite>')
        self.fixture.executable(
            self.fixture.repo / "gradlew",
            self.gradle_body(self.fixture) + """
(r/'app/build/test-results/testDebugUnitTest/TEST-empty.xml').write_text(
 '<testsuite tests="0" failures="0" errors="0" skipped="0"/>')
""")
        result = self.fixture.run()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        receipt = self.fixture.receipt()
        self.assertEqual("ready", receipt["status"])
        self.assertEqual(
            {"tests": 3, "failures": 0, "errors": 0, "skipped": 0, "suites": 2},
            receipt["jvm"])
        self.assertFalse(receipt["published"])
        self.assertFalse(receipt["deployed"])
        self.assertTrue((self.fixture.output / "candidate.apk").is_file())


if __name__ == "__main__":
    unittest.main()
