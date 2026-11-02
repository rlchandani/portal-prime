# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Check CI slideshow selection against the repository's real test modules."""

import glob
from pathlib import Path
import re
import shlex
import unittest


ROOT = Path(__file__).resolve().parents[1]


class SlideshowCiGateTest(unittest.TestCase):
    def setUp(self):
        workflow = (ROOT / ".github/workflows/tests.yml").read_text(encoding="utf-8")
        job = re.search(r"(?ms)^  unit-tests:\n(.*?)(?=^  \S|\Z)", workflow)
        self.assertIsNotNone(job, "Missing unit-tests job")
        # This workflow uses single-line run commands and six-space step lists.
        # Fail closed if the slideshow command moves to another representation.
        self.steps = re.split(r"(?m)^      - ", job.group(1))[1:]
        slideshow_commands = []
        for index, step in enumerate(self.steps):
            run = re.search(r"(?m)^        run: (.+)$", step)
            if run and "slideshow" in run.group(1):
                slideshow_commands.append((index, run.group(1)))
        self.assertEqual(len(slideshow_commands), 1,
                         "Expected one slideshow run command in unit-tests")
        self.slideshow_index, command = slideshow_commands[0]
        arguments = shlex.split(command, posix=False)
        self.assertEqual(arguments[:2], ["node", "--test"])
        self.selected = set()
        for argument in arguments[2:]:
            # Preserve shell quoting: a quoted wildcard is a literal argument.
            if argument.startswith(("'", '"')):
                path = argument[1:-1]
                if (ROOT / path).is_file():
                    self.selected.add(path)
            else:
                self.selected.update(glob.glob(argument, root_dir=ROOT))

    def test_ci_selects_every_slideshow_module(self):
        expected = {
            path.relative_to(ROOT).as_posix()
            for path in (ROOT / "scripts/tests").iterdir()
            if path.is_file()
            and re.fullmatch(r"slideshow_.*\.test\.(?:mjs|cjs)", path.name)
        }
        self.assertTrue(expected, "No slideshow test modules found")
        omitted = sorted(expected - self.selected)
        self.assertEqual(omitted, [], "CI omits slideshow modules:\n" + "\n".join(omitted))

    def test_ci_retains_source_and_label_tests(self):
        required = {
            "scripts/test_slideshow_sources.mjs",
            "scripts/test_slideshow_labels.mjs",
        }
        self.assertEqual(sorted(required - self.selected), [],
                         "CI must retain slideshow source and label tests")

    def test_python_312_setup_precedes_slideshow(self):
        python_steps = [
            index for index, step in enumerate(self.steps)
            if re.search(r"(?m)^        uses: actions/setup-python@", step)
        ]
        self.assertEqual(len(python_steps), 1, "Expected one Python setup step")
        python_index = python_steps[0]
        self.assertLess(python_index, self.slideshow_index,
                        "Cinema grain fixtures need python3 before slideshow tests")
        self.assertRegex(
            self.steps[python_index],
            r"""(?m)^          python-version: ["']?3\.12["']?\s*$""",
        )


if __name__ == "__main__":
    unittest.main()
