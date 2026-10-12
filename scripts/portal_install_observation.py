#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""One install invocation followed by bounded, read-only restart observation.

This module performs no I/O itself. Callers supply the persistent claim, install,
ACK validation, and readback operations. Each receives the remaining seconds from
an ORIGINAL absolute monotonic deadline and must bound its own blocking work to
that budget. Late callback results can never establish readiness.

A ``ready`` result proves only the expected installed byte count/SHA-256 and the
readiness predicate supplied by the caller. Home, Chrome, profile, configuration,
and compilation proofs remain independent caller responsibilities.
"""
import http.client
import math
import re
from threading import Lock
import time


class InstallObserver:
    """A consumed observer cannot invoke install again, including after exceptions.

    ``claim(remaining)`` must atomically and durably consume the caller's install
    permission BEFORE returning literal True. False means it was not acquired.
    The claim must cover the same operation across fresh observers/processes;
    there is no release or automatic retry after an ambiguous result.

    ``install(remaining)`` invokes the installer once and returns a private ACK.
    ``validate_ack(response, remaining)`` returns literal True for an accepted ACK
    or False for an explicit refusal, and raises for malformed/local failures.
    Only install and observe transport errors are caught; claim/ACK validation
    failures propagate. Default transport exceptions exclude general OSError so
    missing local files and permission errors cannot be mistaken for a restart.
    Supplying transport_errors replaces the default tuple; include any standard
    transport classes the caller also wants to catch.

    ``observe(remaining)`` is strictly read-only and returns
    ``(installed_byte_count, lowercase_sha256, service_ready_bool)``. All three
    must describe the current readback; a matching artifact with a busy service
    does not pass. An ACK alone never passes. Transport failures permit another
    readback within the same deadline, never another install invocation.

    Results contain only fixed status/ACK strings, an install-attempt boolean,
    and an observation count. ACK states are not_sent, unvalidated, accepted,
    uncertain, or rejected. A late response/validation yields timeout with an
    unvalidated ACK. Payloads and exception details are never included.
    """

    def __init__(self, expected_bytes, expected_sha256, deadline, *, clock=time.monotonic,
                 sleep=time.sleep, poll_interval=0.5,
                 transport_errors=(ConnectionError, TimeoutError, http.client.IncompleteRead)):
        if type(expected_bytes) is not int or expected_bytes <= 0:
            raise ValueError("Expected byte count must be a positive integer")
        if not self._valid_sha256(expected_sha256):
            raise ValueError("Expected SHA-256 must be 64 lowercase hexadecimal characters")
        if type(deadline) not in (int, float) or not math.isfinite(deadline):
            raise ValueError("Deadline must be an absolute finite monotonic time")
        if (type(poll_interval) not in (int, float) or
                not math.isfinite(poll_interval) or poll_interval <= 0):
            raise ValueError("Poll interval must be finite and positive")
        if not callable(clock) or not callable(sleep):
            raise ValueError("Clock and sleep must be callable")
        if (not isinstance(transport_errors, tuple) or
                any(not isinstance(error, type) or not issubclass(error, Exception) or
                    error is Exception for error in transport_errors)):
            raise ValueError("Transport errors must be a tuple of specific exception classes")
        self._expected_bytes = expected_bytes
        self._expected_sha256 = expected_sha256
        self._deadline = deadline
        self._clock = clock
        self._sleep = sleep
        self._poll_interval = poll_interval
        self._transport_errors = transport_errors
        self._consumed = False
        self._lock = Lock()

    @staticmethod
    def _valid_sha256(value):
        return isinstance(value, str) and re.fullmatch("[a-f0-9]{64}", value) is not None

    def _remaining(self):
        now = self._clock()
        if type(now) not in (int, float) or not math.isfinite(now):
            raise ValueError("Clock must return a finite monotonic time")
        return self._deadline - now

    def run(self, *, claim, install, validate_ack, observe):
        """Consume once, then return ready/rejected/timeout/claim_denied/replay status.

        Local validation errors propagate; the observer remains consumed even if
        a callback raises before returning. Reentrant/concurrent replays return
        ``already_consumed`` immediately without running any supplied callbacks.
        """
        ack = "not_sent"
        attempted = False
        observations = 0

        def result(status):
            return {"status": status, "ack": ack, "install_attempted": attempted,
                    "observations": observations}

        with self._lock:
            if self._consumed:
                return result("already_consumed")
            self._consumed = True
        if not all(callable(callback) for callback in (claim, install, validate_ack, observe)):
            raise ValueError("Install observation callbacks must be callable")
        remaining = self._remaining()
        if remaining <= 0:
            return result("timeout")
        claimed = claim(remaining)
        remaining = self._remaining()
        if remaining <= 0:
            return result("timeout")
        if type(claimed) is not bool:
            raise ValueError("Claim must return a literal boolean")
        if not claimed:
            return result("claim_denied")

        # Record consumption/attempt before entering an installer that may kill
        # its own serving process and disconnect without an ACK.
        attempted = True
        ack = "unvalidated"
        try:
            response = install(remaining)
        except self._transport_errors:
            ack = "uncertain"
        else:
            remaining = self._remaining()
            if remaining <= 0:
                return result("timeout")
            accepted = validate_ack(response, remaining)
            remaining = self._remaining()
            if remaining <= 0:
                return result("timeout")
            if type(accepted) is not bool:
                raise ValueError("ACK validator must return a literal boolean")
            if not accepted:
                ack = "rejected"
                return result("rejected")
            ack = "accepted"

        while True:
            remaining = self._remaining()
            if remaining <= 0:
                return result("timeout")
            observations += 1
            observed = False
            try:
                readback = observe(remaining)
            except self._transport_errors:
                pass
            else:
                observed = True
            remaining = self._remaining()
            if remaining <= 0:
                return result("timeout")
            if observed:
                if (type(readback) not in (tuple, list) or len(readback) != 3 or
                        type(readback[0]) is not int or readback[0] < 0 or
                        not self._valid_sha256(readback[1]) or type(readback[2]) is not bool):
                    raise ValueError("Readback must contain byte count, SHA-256, and readiness boolean")
                if (readback[0] == self._expected_bytes and
                        readback[1] == self._expected_sha256 and readback[2]):
                    # Validation/comparison also consumes time; never accept a
                    # matching result at or after the original deadline.
                    if self._remaining() <= 0:
                        return result("timeout")
                    return result("ready")
            remaining = self._remaining()
            if remaining <= 0:
                return result("timeout")
            self._sleep(min(self._poll_interval, remaining))
            if self._remaining() <= 0:
                return result("timeout")
