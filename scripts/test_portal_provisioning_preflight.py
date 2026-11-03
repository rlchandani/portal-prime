# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Run the production preflight against disposable script-byte fixtures."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parent / "check-provisioning.sh"


class PortalProvisioningPreflightTest(unittest.TestCase):
    def run_guard(self, changes=None, *, optional=True):
        with tempfile.TemporaryDirectory(prefix="portal-provisioning-preflight-test-") as temporary:
            root = Path(temporary)
            (root / "scripts").mkdir()
            (root / "provisioning").mkdir()
            (root / "bin").mkdir()
            guard = root / "scripts" / "check-provisioning.sh"
            shutil.copyfile(SCRIPT, guard)
            files = {
                "provisioning/provision.sh": b"#!/usr/bin/env bash\n# Bash reads UTF-8: \xe2\x80\x94\ntrue\n",
                "provisioning/provision.ps1": b'# Windows script\nWrite-Output "OK"\n',
            }
            if optional:
                files.update({
                    "provisioning/Provision-Portal.bat": b"@echo off\r\nREM Windows launcher\r\n",
                    "scripts/test-provision-access.ps1": b'# Windows test helper\nWrite-Output "OK"\n',
                })
            files.update(changes or {})
            for relative, content in files.items():
                (root / relative).write_bytes(content)
            # Parsing succeeds independently of the byte policy under test.
            # This stub does not execute PowerShell or validate its syntax.
            parser = root / "bin" / "pwsh"
            parser.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="ascii")
            parser.chmod(0o700)
            env = dict(os.environ)
            env["PATH"] = str(root / "bin") + os.pathsep + env.get("PATH", "")
            return subprocess.run(["bash", str(guard)], cwd=root, env=env,
                                  capture_output=True, text=True, timeout=10)

    def assert_accepted(self, result):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Provisioning checks passed.", result.stdout)
        self.assertNotIn("SKIPPED", result.stdout)
        self.assertEqual("", result.stderr)

    def assert_rejected(self, result, target):
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn(target, result.stdout + result.stderr)
        self.assertNotIn("Provisioning checks passed.", result.stdout)

    def test_ascii_windows_scripts_and_utf8_bash_are_accepted(self):
        self.assert_accepted(self.run_guard())

    def test_windows_crlf_and_tabs_are_accepted(self):
        self.assert_accepted(self.run_guard({
            "provisioning/provision.ps1": b'# Windows\tcomment\r\nWrite-Output\t"OK"\r\n',
        }))

    def test_non_ascii_provision_ps1_is_rejected(self):
        target = "provisioning/provision.ps1"
        self.assert_rejected(self.run_guard({
            target: '# Windows comment —\nWrite-Output "OK"\n'.encode("utf-8"),
        }), target)

    def test_non_ascii_bat_is_rejected(self):
        target = "provisioning/Provision-Portal.bat"
        self.assert_rejected(self.run_guard({
            target: "@echo off\r\nREM Windows launcher —\r\n".encode("utf-8"),
        }), target)

    def test_non_ascii_powershell_test_helper_is_rejected(self):
        target = "scripts/test-provision-access.ps1"
        self.assert_rejected(self.run_guard({
            target: '# Windows helper comment —\nWrite-Output "OK"\n'.encode("utf-8"),
        }), target)

    def test_absent_optional_windows_scripts_are_accepted(self):
        self.assert_accepted(self.run_guard(optional=False))

    def test_bash_syntax_failure_still_rejects(self):
        self.assert_rejected(self.run_guard({
            "provisioning/provision.sh": b"#!/usr/bin/env bash\nif true; then\n",
        }), "provision.sh")


if __name__ == "__main__":
    unittest.main()
