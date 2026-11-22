"""tools/scripts/ci-local.sh must run Gradle exactly as .github/workflows/tests.yml does.

The CI unit-test job once died with an OutOfMemoryError that a local run with different
heap flags could not reproduce. Keep the local gate's Gradle command identical to CI's.
"""
from pathlib import Path
import re
import unittest


REPO = Path(__file__).resolve().parents[1]


def workflow_gradle_tokens():
    text = (REPO / ".github" / "workflows" / "tests.yml").read_text(encoding="utf-8")
    match = re.search(r"- name: Run unit tests\n(?:\s+#[^\n]*\n)*\s+run: >-\n((?:\s{10}\S[^\n]*\n)+)", text)
    if match is None:
        raise AssertionError("tests.yml 'Run unit tests' step must use a folded `run: >-` block")
    return match.group(1).split()


def ci_local_gradle_tokens():
    text = (REPO / "tools" / "scripts" / "ci-local.sh").read_text(encoding="utf-8")
    match = re.search(r'^readonly GRADLE_UNIT_TEST_CMD="([^"]+)"$', text, re.M)
    if match is None:
        raise AssertionError("ci-local.sh must declare readonly GRADLE_UNIT_TEST_CMD")
    return match.group(1).split()


class CiLocalParityTest(unittest.TestCase):
    def test_gradle_unit_test_command_matches_the_workflow(self):
        self.assertEqual(workflow_gradle_tokens(), ci_local_gradle_tokens())

    def test_workflow_overrides_the_two_gigabyte_default_heap(self):
        tokens = workflow_gradle_tokens()
        self.assertIn(":app:testDebugUnitTest", tokens)
        self.assertTrue(any(t.startswith("-Dorg.gradle.jvmargs=-Xmx") for t in tokens), tokens)


if __name__ == "__main__":
    unittest.main()
