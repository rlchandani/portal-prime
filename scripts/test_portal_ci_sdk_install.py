"""
Copyright (c) 2026 Starbright Lab.

This source code is licensed under the MIT license found in the
LICENSE file in the root directory of this source tree.
"""

import os
from pathlib import Path
import signal
import subprocess
import tempfile
import unittest


WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/tests.yml"


def sdk_install_script():
    """Read this named step's scalar or literal shell body, without a YAML dependency."""
    lines = WORKFLOW.read_text().splitlines()
    step = lines.index("      - name: Install compile SDK")
    run = lines[step + 1]
    prefix = "        run: "
    if not run.startswith(prefix):
        raise AssertionError("Install compile SDK must provide its shell command")
    value = run[len(prefix):]
    if value != "|":
        return value
    body = []
    for line in lines[step + 2:]:
        if line and not line.startswith("          "):
            break
        body.append(line[10:] if line else "")
    return "\n".join(body)


class CompileSdkInstallTest(unittest.TestCase):
    def check_installer_result(self, expected_exit, pipefail):
        with tempfile.TemporaryDirectory(prefix="immortal-sdk-install-") as directory:
            root = Path(directory)
            arguments = root / "arguments.txt"
            installer = root / "sdkmanager"
            installer.write_text(
                '#!/bin/sh\n'
                'printf "%s\\n" "$@" > "$SDK_FIXTURE_ARGUMENTS"\n'
                'IFS= read -r answer\n'
                '[ "$answer" = y ] || exit 91\n'
                'printf "sdkmanager fixture exit %s\\n" "$SDK_FIXTURE_EXIT" >&2\n'
                'exit "$SDK_FIXTURE_EXIT"\n'
            )
            installer.chmod(0o700)
            command = ["/bin/bash", "--noprofile", "--norc", "-e"]
            if pipefail:
                command += ["-o", "pipefail"]
            command += ["-c", sdk_install_script()]
            process = subprocess.Popen(
                command,
                env={
                    "PATH": directory + os.pathsep + os.defpath,
                    "LC_ALL": "C",
                    "SDK_FIXTURE_ARGUMENTS": str(arguments),
                    "SDK_FIXTURE_EXIT": str(expected_exit),
                },
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                start_new_session=True,
            )
            try:
                output, error = process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate()
                self.fail("The SDK install step did not retire its generated pipeline")
            self.assertEqual(
                expected_exit, process.returncode,
                f"installer exit {expected_exit}, pipefail={pipefail}: {error!r}",
            )
            self.assertEqual(b"", output)
            self.assertIn(f"sdkmanager fixture exit {expected_exit}".encode(), error)
            self.assertEqual(
                ["platforms;android-36", "build-tools;36.0.0"],
                arguments.read_text().splitlines(),
            )

    def test_completed_installer_without_pipefail(self):
        self.check_installer_result(0, False)

    def test_completed_installer_with_pipefail(self):
        self.check_installer_result(0, True)

    def test_exit_two_without_pipefail(self):
        self.check_installer_result(2, False)

    def test_exit_two_with_pipefail(self):
        self.check_installer_result(2, True)

    def test_exit_seven_without_pipefail(self):
        self.check_installer_result(7, False)

    def test_exit_seven_with_pipefail(self):
        self.check_installer_result(7, True)


if __name__ == "__main__":
    unittest.main()
