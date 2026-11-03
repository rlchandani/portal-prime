# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Run only the publishing script's real signing gate; never run its release flow."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("cut-release.sh")
BASH = shutil.which("bash")
SIGNER = "a" * 64


class PublishedSigningGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="portal-release-signing-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.tools = self.root / "tools"
        self.tools.mkdir()
        self.write_tool("curl", """
import os, pathlib, sys
args=sys.argv[1:]
destination=pathlib.Path(args[args.index('-o')+1])
destination.write_text(os.environ['PUBLISHED_CERT'])
sys.exit(int(os.environ['DOWNLOAD_EXIT']))
""")
        self.write_tool("apksigner", """
import pathlib, sys
certificate=pathlib.Path(sys.argv[-1]).read_text()
if certificate == 'invalid': sys.exit(1)
if certificate: print('Signer #1 certificate SHA-256 digest: '+certificate)
""")
        self.candidate = self.root / "candidate.apk"
        self.candidate.write_text(SIGNER)
        self.env = dict(os.environ,
                        PATH=str(self.tools) + os.pathsep + os.environ["PATH"],
                        DOWNLOAD_EXIT="0", PUBLISHED_CERT=SIGNER,
                        apk=str(self.candidate), bt=str(self.tools),
                        tmp=str(self.root), stable_apk_url="https://example.test/immortal.apk")

    def write_tool(self, name, body):
        path = self.tools / name
        path.write_text("#!" + sys.executable + "\n" + body)
        path.chmod(0o755)

    def run_gate(self):
        source = SCRIPT.read_text()
        # The actual gate is evaluated in a separate shell. All preceding authentication,
        # source bumps/builds and subsequent git/GitHub publication commands are excluded.
        start = source.index("\nnew_fp=")
        end = source.index('\nstep "Build portal-kit.zip', start)
        gate = source[start:end]
        shell = ("set -euo pipefail\n"
                 "die(){ printf '%s\\n' \"$*\" >&2; exit 1; }\n" + gate +
                 "\nprintf '%s\\n' 'PACKAGING_GATE_REACHED'\n")
        return subprocess.run([BASH, "-c", shell], env=self.env, cwd=self.root,
                              capture_output=True, text=True, timeout=10)

    def assert_blocked(self, result):
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("PACKAGING_GATE_REACHED", result.stdout)

    def test_download_errors_never_allow_packaging_without_a_signing_baseline(self):
        # HTTP failure (including 404), DNS, timeout and TLS failures are not proof
        # that the product has no previous release. Each curl failure must stop.
        for exit_code in (22, 6, 28, 35, 60):
            with self.subTest(curl_exit=exit_code):
                self.env["DOWNLOAD_EXIT"] = str(exit_code)
                self.assert_blocked(self.run_gate())

    def test_partial_download_is_not_used_as_a_trusted_baseline(self):
        self.env.update(DOWNLOAD_EXIT="28", PUBLISHED_CERT="partial")
        self.assert_blocked(self.run_gate())

    def test_successful_download_with_a_different_certificate_blocks_packaging(self):
        self.env["PUBLISHED_CERT"] = "b" * 64
        self.assert_blocked(self.run_gate())

    def test_successful_download_without_a_certificate_blocks_packaging(self):
        self.env["PUBLISHED_CERT"] = ""
        self.assert_blocked(self.run_gate())

    def test_invalid_published_apk_blocks_packaging(self):
        self.env["PUBLISHED_CERT"] = "invalid"
        self.assert_blocked(self.run_gate())

    def test_unsigned_candidate_blocks_packaging(self):
        self.candidate.write_text("")
        self.assert_blocked(self.run_gate())

    def test_invalid_candidate_blocks_packaging(self):
        self.candidate.write_text("invalid")
        self.assert_blocked(self.run_gate())

    def test_matching_verified_certificates_reach_packaging(self):
        result = self.run_gate()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("PACKAGING_GATE_REACHED", result.stdout)


if __name__ == "__main__":
    unittest.main()
