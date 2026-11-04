# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Exercise the actual release cleanliness gate, excluding fetch and publication."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("cut-release.sh")
BASH = shutil.which("bash")
GIT = shutil.which("git")
BOUNDARY = "FETCH_GATE_REACHED"


class ReleaseCleanTreeGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="portal-release-clean-tree-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.env = dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull,
                        GIT_CONFIG_NOSYSTEM="1", GIT_TERMINAL_PROMPT="0")
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.name", "Generated Release Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.write(".gitignore", "keystore.properties\n*.jks\nbuild/\n.gradle/\n")
        self.write("tracked.txt", "committed\n")
        self.git("add", ".gitignore", "tracked.txt")
        self.git("commit", "-qm", "Generated cleanliness baseline")

    def git(self, *args):
        return subprocess.run([GIT, *args], cwd=self.repo, env=self.env,
                              capture_output=True, text=True, check=True, timeout=10)

    def write(self, name, text="generated, inert fixture\n"):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def run_gate(self):
        source = SCRIPT.read_text()
        # Both baseline and candidate extract the same real block. No branch/authentication
        # preflight, fetch, metadata bump, Android build, signing, or publication is invoked.
        branch = '[ "$(git rev-parse --abbrev-ref HEAD)" = "main" ]'
        start = source.index("\n", source.index(branch)) + 1
        end = source.index("\ngit fetch --quiet origin main --tags", start)
        gate = source[start:end]
        shell = ("set -euo pipefail\n"
                 "die(){ printf '%s\\n' \"$*\" >&2; exit 1; }\n" + gate +
                 "\nprintf '%s\\n' '" + BOUNDARY + "'\n")
        return subprocess.run([BASH, "-c", shell], cwd=self.repo, env=self.env,
                              capture_output=True, text=True, timeout=10)

    def assert_blocked(self, result):
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn(BOUNDARY, result.stdout)

    def assert_accepted(self, result):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(BOUNDARY + "\n", result.stdout)

    def test_clean_committed_tree_reaches_fetch_boundary(self):
        self.assert_accepted(self.run_gate())

    def test_ignored_signing_and_build_outputs_remain_accepted(self):
        for name in ("keystore.properties", "fixture.jks", "app/build/generated/Fixture.kt",
                     ".gradle/generated.bin"):
            self.write(name)
        self.assert_accepted(self.run_gate())

    def test_untracked_kotlin_source_blocks_before_fetch(self):
        self.write("app/src/main/java/com/immortal/launcher/UntrackedFixture.kt")
        self.assert_blocked(self.run_gate())

    def test_untracked_bundled_asset_blocks_before_fetch(self):
        self.write("app/src/main/assets/screensaver/untracked-fixture.svg")
        self.assert_blocked(self.run_gate())

    def test_untracked_weird_filename_blocks_without_printing_its_path(self):
        name = "private fixture\n'--untracked-identity.kt"
        self.write(name)
        result = self.run_gate()
        self.assert_blocked(result)
        for part in ("private fixture", "untracked-identity.kt"):
            self.assertNotIn(part, result.stdout + result.stderr)

    def test_untracked_source_blocks_even_when_status_hides_untracked_files(self):
        self.git("config", "status.showUntrackedFiles", "no")
        self.write("app/src/main/java/com/immortal/launcher/HiddenFixture.kt")
        self.assert_blocked(self.run_gate())

    def test_unstaged_tracked_change_still_blocks(self):
        self.write("tracked.txt", "modified\n")
        self.assert_blocked(self.run_gate())

    def test_staged_tracked_change_still_blocks(self):
        self.write("tracked.txt", "modified\n")
        self.git("add", "tracked.txt")
        self.assert_blocked(self.run_gate())

    def test_ignored_output_does_not_hide_an_untracked_asset(self):
        self.write("app/build/generated/Fixture.kt")
        self.write("app/src/main/assets/untracked-fixture.json")
        self.assert_blocked(self.run_gate())

    def test_untracked_git_query_failure_blocks_before_fetch(self):
        # The real repository and tracked-file queries still use real Git. Only the
        # untracked query fails, so swallowing its status must not admit release.
        tools = self.root / "tools"
        tools.mkdir()
        shim = tools / "git"
        shim.write_text("#!/bin/sh\n"
                        "if [ \"$1\" = ls-files ]; then exit 73; fi\n"
                        "exec \"$CLEAN_TREE_TEST_REAL_GIT\" \"$@\"\n")
        shim.chmod(0o755)
        self.env.update(PATH=str(tools) + os.pathsep + os.environ["PATH"],
                        CLEAN_TREE_TEST_REAL_GIT=GIT)
        self.assert_blocked(self.run_gate())


if __name__ == "__main__":
    unittest.main()
