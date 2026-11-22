"""The A/B noise rule and the build order."""

import unittest

from perflib import compare
from perflib.engine import build_order


def sample(build, step, over32, worst):
    return {"build": build, "step": step, "summary": {"frames": 10, "over32": over32, "worst": worst, "sum_over32": 0,
                                                      "p90": 10, "janky_pct": 0, "over60": 0}}


class VerdictTest(unittest.TestCase):
    def test_win_needs_more_than_the_baseline_spread(self):
        # A median 10, spread 4 (8..12): B must be below 6 to win.
        self.assertEqual(compare.verdict([8, 10, 12], [5, 5, 6])[0], "win")
        self.assertEqual(compare.verdict([8, 10, 12], [6, 6, 6])[0], "noise")  # exactly the spread: not a win
        self.assertEqual(compare.verdict([8, 10, 12], [7, 9, 9])[0], "noise")

    def test_loss_and_identical(self):
        self.assertEqual(compare.verdict([8, 10, 12], [15, 15, 16])[0], "loss")
        self.assertEqual(compare.verdict([5, 5, 5], [5, 5, 5])[0], "noise")
        self.assertEqual(compare.verdict([5, 5, 5], [4, 4, 4])[0], "win")  # zero spread: any real drop wins

    def test_missing_side(self):
        self.assertEqual(compare.verdict([], [1])[0], "n/a")

    def test_median_and_spread_reported(self):
        self.assertEqual(compare.verdict([3, 1, 2], [0, 0, 1]), ("noise", 2, 2, 0))  # 0 is not < 2 - 2
        self.assertEqual(compare.verdict([3, 1, 2], [-1, 0, -1])[0], "win")

    def test_rows_and_table(self):
        result = {"builds": [{"label": "O"}, {"label": "B"}],
                  "samples": [sample("O", "open", 5, 90), sample("O", "open", 6, 100), sample("O", "open", 5, 95),
                              sample("B", "open", 1, 40), sample("B", "open", 0, 38), sample("B", "open", 1, 41)]}
        rows = {(r[0], r[1]): r[-1] for r in compare.rows(result)}
        self.assertEqual(rows[("open", "over32")], "win")
        self.assertEqual(rows[("open", "worst")], "win")
        self.assertEqual(rows[("open", "p90")], "noise")
        self.assertIn("**win**", compare.table(result))
        self.assertIn("needs at least two builds", compare.table({"builds": [{"label": "O"}], "samples": []}))


class OrderTest(unittest.TestCase):
    def test_interleaved_abba(self):
        self.assertEqual(build_order(["A", "B"], 4, True), list("ABBAABBA"))

    def test_blocked(self):
        self.assertEqual(build_order(["A", "B"], 2, False), list("AABB"))

    def test_single_build(self):
        self.assertEqual(build_order(["O"], 3, True), list("OOO"))


if __name__ == "__main__":
    unittest.main()
