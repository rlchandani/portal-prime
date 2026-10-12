#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Pure callback tests: no device, network, subprocess, or wall-clock waits."""
import http.client
import json
import unittest

from portal_install_observation import InstallObserver


SHA256 = "a" * 64


class FakeClock:
    def __init__(self):
        self.now = 100.0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class Rig:
    """Callbacks model durable consumption and independently controlled readbacks."""
    def __init__(self):
        self.clock = FakeClock()
        self.events = []
        self.claimed = False
        self.durations = {}
        self.install_action = lambda remaining: {"accepted": True, "token": "private-payload"}
        self.ack_action = lambda response, remaining: response["accepted"]
        self.observe_action = lambda remaining: (123, SHA256, True)

    def record(self, name, remaining):
        self.events.append((name, remaining))
        self.clock.now += self.durations.get(name, 0)

    def claim(self, remaining):
        self.record("claim", remaining)
        if self.claimed:
            return False
        self.claimed = True
        return True

    def install(self, remaining):
        self.record("install", remaining)
        return self.install_action(remaining)

    def validate_ack(self, response, remaining):
        self.record("ack", remaining)
        return self.ack_action(response, remaining)

    def observe(self, remaining):
        self.record("observe", remaining)
        return self.observe_action(remaining)

    def observer(self, **kwargs):
        return InstallObserver(123, SHA256, 103.0, clock=self.clock,
                               sleep=self.clock.sleep, poll_interval=1.0, **kwargs)

    def run(self, observer=None, **kwargs):
        callbacks = dict(claim=self.claim, install=self.install,
                         validate_ack=self.validate_ack, observe=self.observe)
        callbacks.update(kwargs)
        return (observer or self.observer()).run(**callbacks)

    def names(self):
        return [name for name, remaining in self.events]


def disconnected(remaining):
    raise http.client.RemoteDisconnected("secret-url-and-credential")


class InstallObservationTest(unittest.TestCase):
    def test_remote_disconnect_observes_exact_ready_apk_after_one_install(self):
        clock = FakeClock()
        events = []

        def claim(remaining):
            events.append("claim")
            return True

        def install(remaining):
            events.append("install")
            raise http.client.RemoteDisconnected("sensitive transport details")

        def validate_ack(response, remaining):
            self.fail("Disconnected transport cannot provide an ACK")

        def observe(remaining):
            events.append("observe")
            return 123, SHA256, True

        observer = InstallObserver(123, SHA256, 105.0, clock=clock, sleep=clock.sleep)
        try:
            result = observer.run(claim=claim, install=install,
                                  validate_ack=validate_ack, observe=observe)
        except http.client.RemoteDisconnected:
            result = {"status": "aborted_before_observation"}
        self.assertEqual("ready", result["status"])
        self.assertEqual(["claim", "install", "observe"], events)


    def test_valid_ack_cannot_pass_wrong_bytes_or_sha(self):
        for observation in ((124, SHA256, True), (123, "b" * 64, True)):
            with self.subTest(observation=observation):
                rig = Rig()
                rig.observe_action = lambda remaining: observation
                result = rig.run()
                self.assertEqual("timeout", result["status"])
                self.assertEqual("accepted", result["ack"])
                self.assertEqual(1, rig.names().count("install"))
                self.assertEqual(103.0, rig.clock.now)

    def test_exact_hash_waits_for_idle_service_readiness(self):
        rig = Rig()
        observations = iter(((123, SHA256, False), (123, SHA256, True)))
        rig.observe_action = lambda remaining: next(observations)
        result = rig.run()
        self.assertEqual("ready", result["status"])
        self.assertEqual(2, result.get("observations"))
        self.assertEqual(1, rig.names().count("install"))
        self.assertEqual(101.0, rig.clock.now)

    def test_busy_service_expires_at_original_deadline(self):
        rig = Rig()
        rig.observe_action = lambda remaining: (123, SHA256, False)
        result = rig.run()
        self.assertEqual("timeout", result["status"])
        self.assertEqual(3, result.get("observations"))
        self.assertEqual(103.0, rig.clock.now)
        self.assertTrue(all(0 < remaining <= 3 for name, remaining in rig.events))

    def test_transient_read_transport_failure_retries_only_observation(self):
        rig = Rig()
        rig.install_action = disconnected
        reads = iter((False, True))

        def observe(remaining):
            if not next(reads):
                raise ConnectionResetError("private read error")
            return 123, SHA256, True

        rig.observe_action = observe
        result = rig.run()
        self.assertEqual("ready", result["status"])
        self.assertEqual("uncertain", result.get("ack"))
        self.assertEqual(["claim", "install", "observe", "observe"], rig.names())

    def test_explicit_rejection_aborts_without_polling(self):
        rig = Rig()
        rig.install_action = lambda remaining: {"accepted": False}
        result = rig.run()
        self.assertEqual("rejected", result["status"])
        self.assertEqual("rejected", result.get("ack"))
        self.assertEqual(["claim", "install", "ack"], rig.names())

    def test_malformed_ack_is_validation_failure_without_polling(self):
        for ack in (None, 1, {"accepted": True}):
            with self.subTest(ack=ack):
                rig = Rig()
                rig.ack_action = lambda response, remaining: ack
                with self.assertRaises(ValueError):
                    rig.run()
                self.assertEqual(["claim", "install", "ack"], rig.names())

    def test_local_validation_failures_propagate_and_consume_the_observer(self):
        for stage in ("install", "ack", "observe"):
            with self.subTest(stage=stage):
                rig = Rig()
                observer = rig.observer()

                def invalid(*args):
                    raise ValueError("local-validation-secret")

                setattr(rig, stage + "_action", invalid)
                with self.assertRaises(ValueError):
                    rig.run(observer)
                result = rig.run(observer)
                self.assertEqual("already_consumed", result["status"])
                self.assertEqual(1, rig.names().count("install"))
                if stage != "observe":
                    self.assertNotIn("observe", rig.names())

    def test_local_oserror_is_not_classified_as_transport(self):
        rig = Rig()

        def invalid(remaining):
            raise FileNotFoundError("private APK path")

        rig.install_action = invalid
        with self.assertRaises(FileNotFoundError):
            rig.run()
        self.assertEqual(["claim", "install"], rig.names())

    def test_transport_exception_in_ack_validation_is_not_swallowed(self):
        rig = Rig()

        def invalid(response, remaining):
            raise http.client.RemoteDisconnected("validation transport is not install transport")

        rig.ack_action = invalid
        with self.assertRaises(http.client.RemoteDisconnected):
            rig.run()
        self.assertEqual(["claim", "install", "ack"], rig.names())

    def test_expiry_after_each_callback_never_passes_or_calls_next_stage(self):
        for stage, names in (("claim", ["claim"]),
                             ("install", ["claim", "install"]),
                             ("ack", ["claim", "install", "ack"]),
                             ("observe", ["claim", "install", "ack", "observe"])):
            with self.subTest(stage=stage):
                rig = Rig()
                rig.durations[stage] = 3.0
                result = rig.run()
                self.assertEqual("timeout", result["status"])
                self.assertEqual(names, rig.names())
                self.assertEqual(stage != "claim", result.get("install_attempted"))
                expected_ack = {"claim": "not_sent", "install": "unvalidated",
                                "ack": "unvalidated", "observe": "accepted"}
                self.assertEqual(expected_ack[stage], result.get("ack"))

    def test_transport_failure_after_deadline_cannot_start_polling(self):
        rig = Rig()
        rig.durations["install"] = 3.0
        rig.install_action = disconnected
        result = rig.run()
        self.assertEqual("timeout", result["status"])
        self.assertTrue(result.get("install_attempted"))
        self.assertEqual(["claim", "install"], rig.names())

    def test_every_callback_receives_current_remaining_budget(self):
        rig = Rig()
        rig.durations = dict(claim=0.25, install=0.25, ack=0.25, observe=0.25)
        self.assertEqual("ready", rig.run()["status"])
        self.assertEqual([("claim", 3.0), ("install", 2.75),
                          ("ack", 2.5), ("observe", 2.25)], rig.events)

    def test_replays_cannot_send_again_even_after_uncertain_ack(self):
        rig = Rig()
        rig.install_action = disconnected
        observer = rig.observer()
        result = rig.run(observer)
        self.assertEqual("ready", result["status"])
        self.assertEqual("already_consumed", rig.run(observer)["status"])
        self.assertEqual("claim_denied", rig.run(rig.observer())["status"])
        self.assertEqual(1, rig.names().count("install"))
        self.assertEqual(2, rig.names().count("claim"))
        self.assertEqual(1, rig.names().count("observe"))

    def test_claim_denial_prevents_install(self):
        rig = Rig()
        rig.claimed = True
        observer = rig.observer()
        result = rig.run(observer)
        self.assertEqual("claim_denied", result["status"])
        self.assertFalse(result.get("install_attempted"))
        self.assertEqual("already_consumed", rig.run(observer)["status"])
        self.assertEqual(["claim"], rig.names())

    def test_claim_exception_prevents_install_and_cannot_be_replayed(self):
        for error in (ValueError, OSError, http.client.RemoteDisconnected):
            with self.subTest(error=error.__name__):
                rig = Rig()
                observer = rig.observer()

                def failed_claim(remaining):
                    rig.record("claim", remaining)
                    raise error("private claim details")

                with self.assertRaises(error):
                    rig.run(observer, claim=failed_claim)
                self.assertEqual("already_consumed", rig.run(observer)["status"])
                self.assertEqual(["claim"], rig.names())

    def test_expired_deadline_has_no_callback_side_effects(self):
        rig = Rig()
        rig.clock.now = 103.0
        result = rig.run()
        self.assertEqual("timeout", result["status"])
        self.assertFalse(result.get("install_attempted"))
        self.assertEqual([], rig.events)

    def test_only_explicit_custom_transport_is_observed(self):
        class CustomTransport(RuntimeError):
            pass

        def failure(remaining):
            raise CustomTransport("private custom error")

        rig = Rig()
        rig.install_action = failure
        with self.assertRaises(CustomTransport):
            rig.run()
        self.assertNotIn("observe", rig.names())
        rig = Rig()
        rig.install_action = failure
        result = rig.run(rig.observer(transport_errors=(CustomTransport,)))
        self.assertEqual("ready", result["status"])
        self.assertEqual("uncertain", result.get("ack"))
        self.assertEqual(1, rig.names().count("install"))

    def test_malformed_readback_cannot_pass_or_be_retried(self):
        for observation in ((True, SHA256, True), (123, "bad-hash", True),
                            (123, SHA256, 1), {"bytes": 123, "sha256": SHA256}):
            with self.subTest(observation=observation):
                rig = Rig()
                rig.observe_action = lambda remaining: observation
                with self.assertRaises(ValueError):
                    rig.run()
                self.assertEqual(1, rig.names().count("observe"))

    def test_sleep_is_bounded_and_oversleep_cannot_pass(self):
        rig = Rig()
        rig.clock.now = 102.75
        rig.observe_action = lambda remaining: (123, SHA256, False)
        sleeps = []

        def oversleep(seconds):
            sleeps.append(seconds)
            rig.clock.now += seconds + 1.0

        rig.clock.sleep = oversleep
        result = rig.run()
        self.assertEqual("timeout", result["status"])
        self.assertEqual([0.25], sleeps)
        self.assertEqual(1, rig.names().count("observe"))

    def test_reentrant_call_cannot_repeat_install(self):
        rig = Rig()
        observer = rig.observer()

        def install(remaining):
            self.assertEqual("already_consumed", rig.run(observer)["status"])
            return {"accepted": True}

        rig.install_action = install
        self.assertEqual("ready", rig.run(observer)["status"])
        self.assertEqual(1, rig.names().count("claim"))
        self.assertEqual(1, rig.names().count("install"))

    def test_public_result_contains_only_safe_scalars(self):
        for uncertain in (False, True):
            with self.subTest(uncertain=uncertain):
                rig = Rig()
                if uncertain:
                    rig.install_action = disconnected
                result = rig.run()
                self.assertEqual("ready", result["status"])
                self.assertEqual({"status", "ack", "install_attempted", "observations"}, set(result))
                self.assertTrue(all(type(value) in (str, bool, int) for value in result.values()))
                public = json.dumps(result)
                self.assertNotIn("secret", public)
                self.assertNotIn("private", public)
                self.assertNotIn(SHA256, public)

    def test_invalid_configuration_is_rejected_before_claim(self):
        for kwargs in ({"expected_bytes": True}, {"expected_bytes": 0},
                       {"expected_sha256": "bad"}, {"deadline": float("nan")},
                       {"deadline": float("inf")}, {"poll_interval": 0},
                       {"poll_interval": float("nan")},
                       {"transport_errors": (Exception,)}):
            with self.subTest(kwargs=kwargs):
                rig = Rig()
                config = dict(expected_bytes=123, expected_sha256=SHA256, deadline=103.0,
                              clock=rig.clock, sleep=rig.clock.sleep)
                config.update(kwargs)
                with self.assertRaises(ValueError):
                    InstallObserver(**config)
                self.assertEqual([], rig.events)


if __name__ == "__main__":
    unittest.main()
