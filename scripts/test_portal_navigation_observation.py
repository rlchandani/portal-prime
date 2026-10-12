#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
import unittest

from portal_navigation_observation import (NavigationObservationError,
                                          read_owned_sample, wait_for_navigation)

PACKAGE = "com.immortal.launcher.debug"
UI = {"ok": True, "page": "settings"}
GAP = {"httpStatus": 409, "error": "home_ui_application_window_required"}


class Clock:
    def __init__(self):
        self.now = 0.0
    def __call__(self):
        return self.now
    def sleep(self, seconds):
        self.now += seconds


def pin(window=7, package=PACKAGE, busy=False):
    return {"foregroundPackage": package, "foregroundWindowId": window, "busy": busy}


def idle(value):
    return type(value) is dict and value.get("busy") is False


class Samples(unittest.TestCase):
    def observe(self, before, after, ui=UI, transition=False, late=None):
        clock = Clock()
        calls = []
        pins = iter((before, after))
        def read_pin(end):
            calls.append(("pin", end))
            value = next(pins)
            if late == len(calls):
                clock.now = end
            return value
        def read_ui(end, allow):
            calls.append(("ui", end, allow))
            if late == len(calls):
                clock.now = end
            return ui
        result = read_owned_sample(read_pin=read_pin, read_ui=read_ui, idle=idle,
                                   package=PACKAGE, deadline=1, allow_transition=transition,
                                   clock=clock)
        return result, calls

    def test_stable_owned_sample_retains_exact_public_payload(self):
        result, calls = self.observe(pin(), pin())
        self.assertIs(result[0], UI)
        self.assertEqual(result[1], 7)
        self.assertEqual(calls, [("pin", 1), ("ui", 1, False), ("pin", 1)])

    def test_owned_window_change_during_admitted_transition_discards_payload(self):
        result, calls = self.observe(pin(7), pin(8), transition=True)
        self.assertIsNone(result)
        self.assertEqual(calls[1], ("ui", 1, True))

    def test_strict_read_cannot_authorize_input_from_changed_window(self):
        with self.assertRaisesRegex(NavigationObservationError, "^owned_window_changed$"):
            self.observe(pin(7), pin(8))

    def test_foreign_owner_is_never_a_transition_gap(self):
        for before, after in ((pin(package="org.chromium.chrome"), pin()),
                              (pin(), pin(package="org.chromium.chrome")),
                              (pin(), pin(None, "org.chromium.chrome"))):
            with self.subTest(before=before, after=after):
                with self.assertRaisesRegex(NavigationObservationError, "^foreign_owner$"):
                    self.observe(before, after, transition=True)

    def test_busy_pin_is_fatal_at_either_boundary(self):
        for before, after in ((pin(busy=True), pin()), (pin(), pin(busy=True))):
            with self.assertRaisesRegex(NavigationObservationError, "^surface_not_idle$"):
                self.observe(before, after, transition=True)

    def test_missing_owner_fields_cannot_be_inferred_as_gap(self):
        for key in ("foregroundPackage", "foregroundWindowId"):
            value = pin(); del value[key]
            with self.assertRaisesRegex(NavigationObservationError, "^owner_fields_missing$"):
                self.observe(value, pin(), transition=True)

    def test_invalid_window_types_are_rejected(self):
        for window in (True, -1, "7"):
            with self.subTest(window=window):
                with self.assertRaises(NavigationObservationError):
                    self.observe(pin(window), pin(), transition=True)

    def test_missing_ownership_with_known_409_is_admitted_only_in_transition(self):
        self.assertIsNone(self.observe(pin(None), pin(None), ui=GAP, transition=True)[0])
        self.assertIsNone(self.observe(pin(None, None), pin(None, None), ui=GAP,
                                       transition=True)[0])
        with self.assertRaisesRegex(NavigationObservationError, "^owner_missing$"):
            self.observe(pin(None), pin(None), ui=GAP)

    def test_one_missing_boundary_discards_ui_until_owner_stabilizes(self):
        self.assertIsNone(self.observe(pin(None), pin(8), transition=True)[0])
        self.assertIsNone(self.observe(pin(7), pin(None), transition=True)[0])

    def test_missing_both_boundaries_cannot_claim_successful_ui(self):
        with self.assertRaisesRegex(NavigationObservationError, "^missing_owner_ui_mismatch$"):
            self.observe(pin(None), pin(None), transition=True)

    def test_owned_gap_in_middle_is_never_a_page_proof(self):
        self.assertIsNone(self.observe(pin(), pin(), ui=GAP, transition=True)[0])
        with self.assertRaisesRegex(NavigationObservationError, "^unexpected_ui_gap$"):
            self.observe(pin(), pin(), ui=GAP)

    def test_unrecognized_or_malformed_ui_is_fatal(self):
        for ui in ({"httpStatus": 409, "error": "other"}, {"ok": False}, [], None):
            with self.assertRaisesRegex(NavigationObservationError, "^invalid_ui_response$"):
                self.observe(pin(), pin(), ui=ui, transition=True)

    def test_late_callback_cannot_prove_a_page(self):
        for call in (1, 2, 3):
            with self.subTest(call=call):
                with self.assertRaisesRegex(NavigationObservationError, "^navigation_timeout$"):
                    self.observe(pin(), pin(), late=call)

    def test_local_permission_or_transport_errors_are_not_swallowed(self):
        for error in (PermissionError("private details"), ConnectionError("private details")):
            with self.subTest(error=type(error).__name__):
                def fail(end):
                    raise error
                with self.assertRaises(type(error)) as raised:
                    read_owned_sample(read_pin=fail, read_ui=lambda *args: UI, idle=idle,
                                      package=PACKAGE, deadline=1, clock=Clock())
                self.assertIs(raised.exception, error)

    def test_invalid_deadlines_and_flags_fail_before_any_read(self):
        for deadline in (True, float("nan"), float("inf")):
            with self.assertRaisesRegex(NavigationObservationError, "^invalid_deadline$"):
                read_owned_sample(read_pin=lambda *args:self.fail("unexpected read"),
                                  read_ui=lambda *args:self.fail("unexpected read"), idle=idle,
                                  package=PACKAGE, deadline=deadline, clock=Clock())


class Waiting(unittest.TestCase):
    def wait(self, samples, *, settle=0.1, before=None, prove=None):
        clock = Clock(); calls = []; values = iter(samples)
        def read(end, allow):
            calls.append((end, allow, clock.now))
            if before:
                before(clock, len(calls), end)
            return next(values)
        result = wait_for_navigation(read_sample=read, prove=prove or
                                     (lambda ui, window: (window, ui["page"]) if ui["page"] == "settings" else None),
                                     deadline=2, clock=clock, sleep=clock.sleep,
                                     poll_interval=0.1, settle_time=settle)
        return result, calls

    def test_source_and_transition_gap_never_establish_destination(self):
        result, calls = self.wait([({"page": "home"}, 7), None, (UI, 8), (UI, 8)])
        self.assertEqual(result, (8, "settings"))
        self.assertEqual([x[1] for x in calls], [True, True, True, False])
        self.assertTrue(all(x[0] == 2 for x in calls))

    def test_gap_resets_stability_and_requires_fresh_destination_samples(self):
        result, calls = self.wait([None, None, (UI, 8), (UI, 8)])
        self.assertEqual(result, (8, "settings"))
        self.assertEqual(len(calls), 4)

    def test_destination_owner_loss_after_first_proof_is_fatal(self):
        with self.assertRaisesRegex(NavigationObservationError, "^destination_owner_lost$"):
            self.wait([(UI, 8), None])

    def test_return_to_source_after_destination_is_fatal(self):
        with self.assertRaisesRegex(NavigationObservationError, "^destination_lost$"):
            self.wait([(UI, 8), ({"page": "home"}, 7)])

    def test_changed_geometry_requires_settling_again(self):
        first = {"page": "settings", "geometry": 1}
        final = {"page": "settings", "geometry": 2}
        result, calls = self.wait([(first, 8), (final, 8), (final, 8)],
                                 prove=lambda ui, window:(window, ui["geometry"]))
        self.assertEqual(result, (8, 2)); self.assertEqual(len(calls), 3)

    def test_same_destination_requires_two_observations_and_settling_time(self):
        result, calls = self.wait([(UI, 8)] * 4, settle=0.25)
        self.assertEqual(result, (8, "settings")); self.assertEqual(len(calls), 4)

    def test_late_read_result_never_establishes_destination(self):
        def late(clock, count, end):
            if count == 2:
                clock.now = end
        with self.assertRaisesRegex(NavigationObservationError, "^navigation_timeout$"):
            self.wait([(UI, 8), (UI, 8)], before=late)

    def test_late_proof_never_establishes_destination(self):
        clock = Clock()
        def prove(ui, window):
            clock.now = 1
            return (window, "settings")
        with self.assertRaisesRegex(NavigationObservationError, "^navigation_timeout$"):
            wait_for_navigation(read_sample=lambda *args:(UI, 8), prove=prove,
                                deadline=1, clock=clock, sleep=clock.sleep)

    def test_only_gaps_timeout_without_dispatching_any_input(self):
        clock = Clock(); deadlines = []
        def read(end, allow):
            deadlines.append((end, allow)); return None
        with self.assertRaisesRegex(NavigationObservationError, "^navigation_timeout$"):
            wait_for_navigation(read_sample=read, prove=lambda *args:self.fail("no page"),
                                deadline=0.3, clock=clock, sleep=clock.sleep)
        self.assertTrue(deadlines); self.assertTrue(all(x == (0.3, True) for x in deadlines))

    def test_proof_failures_are_not_retried_or_hidden(self):
        error = ValueError("private details"); calls = []
        def prove(*args):
            calls.append(True); raise error
        with self.assertRaises(ValueError) as raised:
            self.wait([(UI, 8)], prove=prove)
        self.assertIs(raised.exception, error); self.assertEqual(len(calls), 1)

    def test_invalid_samples_and_mutable_proofs_are_rejected(self):
        for sample in ([], (UI, True), (UI, -1), (None, 8)):
            with self.assertRaisesRegex(NavigationObservationError, "^invalid_sample$"):
                self.wait([sample])
        with self.assertRaisesRegex(NavigationObservationError, "^invalid_proof$"):
            self.wait([(UI, 8)], prove=lambda *args:[])


if __name__ == "__main__":
    unittest.main()
