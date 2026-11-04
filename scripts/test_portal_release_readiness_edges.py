# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Negative CLI contracts; build and SDK processes use the shared fixture."""
import json
import os
import shlex
import signal
import time
import unittest

from test_portal_release_readiness import PASSING_JVM_REPORT, ReadinessFixture


class ReleaseReadinessEdgesTest(unittest.TestCase):
    def setUp(self):
        self.fixture = ReadinessFixture()
        self.addCleanup(self.fixture.close)

    def assert_failed(self, fixture, result, error_pattern):
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue((fixture.output / "receipt.json").is_file(), result.stderr)
        receipt = fixture.receipt()
        self.assertEqual("failed", receipt["status"])
        self.assertFalse(receipt["published"])
        self.assertFalse(receipt["deployed"])
        self.assertRegex(receipt["error"].lower(), error_pattern)
        self.assertFalse((fixture.output / "candidate.apk").exists())
        return receipt

    @staticmethod
    def gradle_body(fixture):
        return (fixture.repo / "gradlew").read_text().split("\n", 1)[1]

    def set_jvm_report(self, fixture, report):
        original = PASSING_JVM_REPORT
        body = self.gradle_body(fixture)
        self.assertIn(original, body)
        fixture.executable(fixture.repo / "gradlew", body.replace(original, report))

    def test_failed_gradle_has_actionable_receipt_without_inline_process_logs(self):
        f = self.fixture
        f.write("fail-gradle", "")
        marker = "fixture-process-output-do-not-inline"
        f.executable(f.repo / "gradlew",
                     f"import sys\nprint({marker!r})\nprint({marker!r}, file=sys.stderr)\n"
                     + self.gradle_body(f))
        result = f.run()
        receipt = self.assert_failed(f, result, r"gradle|build|gate")
        self.assertNotIn(marker, result.stdout + result.stderr)
        self.assertNotIn(marker, json.dumps(receipt))
        self.assertTrue(any(marker in path.read_text(errors="replace")
                            for path in f.output.rglob("*.log")),
                        "Process output must remain available in a gate log")

    def test_missing_jvm_reports_cannot_report_ready(self):
        f = self.fixture
        f.write("missing-tests", "")
        self.assert_failed(f, f.run(), r"jvm|report|test")

    def test_zero_test_or_empty_jvm_reports_cannot_report_ready(self):
        for report in ('<testsuite tests="0" failures="0" errors="0" skipped="0"/>', ""):
            with self.subTest(report=report):
                f = ReadinessFixture()
                self.addCleanup(f.close)
                self.set_jvm_report(f, report)
                self.assert_failed(f, f.run(), r"jvm|report|test|zero")

    def test_failed_or_errored_jvm_report_overrides_successful_gradle_exit(self):
        for failures, errors in ((1, 0), (0, 1)):
            with self.subTest(failures=failures, errors=errors):
                f = ReadinessFixture()
                self.addCleanup(f.close)
                self.set_jvm_report(
                    f, f'<testsuite tests="3" failures="{failures}" errors="{errors}" skipped="0"/>')
                self.assert_failed(f, f.run(), r"jvm|report|test|fail|error")

    def test_unsigned_baseline_is_rejected(self):
        f = self.fixture
        f.baseline.write_text(json.dumps(dict(f.metadata, code=61, signer="")))
        self.assert_failed(f, f.run(), r"sign|verify|certificate")

    def test_unsigned_candidate_is_rejected(self):
        f = self.fixture
        f.write("candidate.json", json.dumps(dict(f.metadata, signer="")))
        self.assert_failed(f, f.run(), r"sign|verify|certificate")

    def test_wrong_candidate_package_is_rejected(self):
        f = self.fixture
        f.write("candidate.json", json.dumps(dict(f.metadata, package="com.example.other")))
        self.assert_failed(f, f.run(), r"package")

    def test_missing_baseline_is_a_failure_without_signer_fallback(self):
        f = self.fixture
        f.baseline.unlink()
        self.assert_failed(f, f.run(), r"baseline|apk")

    def test_existing_output_receipt_and_candidate_are_preserved(self):
        f = self.fixture
        f.output.mkdir()
        sentinels = {"receipt.json": b"accepted receipt sentinel\n",
                     "candidate.apk": b"accepted candidate sentinel\n"}
        for name, contents in sentinels.items():
            (f.output / name).write_bytes(contents)
        result = f.run()
        self.assertNotEqual(0, result.returncode)
        self.assertRegex((result.stdout + result.stderr).lower(), r"output|exist")
        self.assertEqual(set(sentinels), {p.name for p in f.output.iterdir()})
        for name, contents in sentinels.items():
            self.assertEqual(contents, (f.output / name).read_bytes())

    @unittest.skipUnless(os.name == "posix", "Process-group timeout contract is POSIX")
    def test_timeout_terminates_gradle_child_before_delayed_side_effect(self):
        f = self.fixture
        started = f.root / "child-started"
        survived = f.root / "child-survived"
        # Reserve the one-second deadline for Gradle, rather than interpreter
        # startup in the external Node/SDK fixtures. The actual CLI stays real.
        fast_tools = {
            f.tools / "node": (
                'if [ "$1" = "--version" ]; then printf "v24.0.0\\n"; '
                'else printf "# tests 1\\n"; fi\n'
            ),
            f.sdk / "build-tools/36.0.0/aapt": (
                'printf "%s\\n" "package: name=\'com.immortal.launcher.debug\' '
                'versionCode=\'61\' versionName=\'1.67\'"\n'
            ),
            f.sdk / "build-tools/36.0.0/apksigner": (
                f'printf "%s\\n" "Signer #1 certificate SHA-256 digest: {"a" * 64}"\n'
            ),
        }
        for path, body in fast_tools.items():
            path.write_text("#!/bin/sh\n" + body)
            path.chmod(0o755)
        child = (
            f"printf '%s\\n' \"$$\" > {shlex.quote(str(started))}\n"
            "sleep 2\n"
            f"printf '%s\\n' 'child survived timeout' > {shlex.quote(str(survived))}\n"
        )
        f.write("sleep-gradle", "")
        gradle = f.repo / "gradlew"
        gradle.write_text("#!/bin/sh\n"
                          f"/bin/sh -c {shlex.quote(child)} >/dev/null 2>&1 &\n"
                          "if [ -f sleep-gradle ]; then sleep 30; fi\n")
        gradle.chmod(0o755)

        def cleanup_child():
            if started.exists():
                try:
                    os.kill(int(started.read_text()), signal.SIGKILL)
                except ProcessLookupError:
                    pass

        self.addCleanup(cleanup_child)
        before = time.monotonic()
        result = f.run("--timeout-seconds", "1")
        self.assert_failed(f, result, r"timeout|timed out")
        self.assertTrue(started.is_file(),
                        "The child must have actually started: " + json.dumps(f.receipt()))
        self.assertLess(time.monotonic() - before, 10, "Timeout must bound the CLI run")
        time.sleep(2.2)
        self.assertFalse(survived.exists(), "A child in the timed-out group survived")

    def test_source_changed_by_gradle_invalidates_the_receipt(self):
        f = self.fixture
        source = f.write("app/src/main/source.kt", "// original source\n")
        f.git("add", "app/src/main/source.kt")
        body = self.gradle_body(f).replace(
            "r=pathlib.Path.cwd()",
            "r=pathlib.Path.cwd()\n"
            "(r/'app/src/main/source.kt').write_text('// changed during checks\\n')",
        )
        f.executable(f.repo / "gradlew", body)
        result = f.run()
        self.assert_failed(f, result, r"source|input|chang")
        self.assertEqual("// changed during checks\n", source.read_text())

    def test_missing_sdk_or_failed_sdk_tool_cannot_report_ready(self):
        for scenario in ("missing-sdk", "aapt", "apksigner"):
            with self.subTest(scenario=scenario):
                f = ReadinessFixture()
                self.addCleanup(f.close)
                if scenario == "missing-sdk":
                    extra = ("--sdk", str(f.root / "absent-sdk"))
                else:
                    f.executable(f.sdk / "build-tools/36.0.0" / scenario, "raise SystemExit(9)\n")
                    extra = ()
                self.assert_failed(f, f.run(*extra),
                                   r"sdk|aapt|apksigner|sign|metadata|build.tools")


if __name__ == "__main__":
    unittest.main()
