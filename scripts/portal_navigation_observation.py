#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Bounded, read-only observation after a separately claimed navigation input.

This module performs no I/O or input dispatch. HTTP callbacks must honor the
same absolute monotonic deadline supplied here. A bracketed snapshot whose
owned window changed is discarded ONLY while awaiting an admitted transition;
it cannot establish a page, authorize input, or hide a foreign/busy owner.
Public page geometry and selectors remain the caller's proof responsibility.
"""
import math
import time


class NavigationObservationError(RuntimeError):
    """Fixed diagnostic code; no response, UI or credential is included."""


def _require(value, code):
    if not value:
        raise NavigationObservationError(code)


def _check(deadline, clock):
    _require(type(deadline) in (int, float) and math.isfinite(deadline), "invalid_deadline")
    now = clock()
    _require(type(now) in (int, float) and math.isfinite(now), "invalid_clock")
    _require(now < deadline, "navigation_timeout")
    return now


def _owned_window(pin, idle, package, allow_transition):
    _require(type(pin) is dict and idle(pin) is True, "surface_not_idle")
    _require("foregroundPackage" in pin and "foregroundWindowId" in pin, "owner_fields_missing")
    app, window = pin["foregroundPackage"], pin["foregroundWindowId"]
    if app == package and type(window) is int and window >= 0:
        return window
    _require(app in (None, package), "foreign_owner")
    _require(window is None, "invalid_owner")
    _require(allow_transition, "owner_missing")
    return None


def read_owned_sample(*, read_pin, read_ui, idle, package, deadline,
                      allow_transition=False, clock=time.monotonic):
    """Return (public UI, owned window), or None for an admitted transition gap.

    read_pin(deadline) returns an idle checked pin. read_ui(deadline,
    allow_transition) returns the public UI or exactly the known 409 gap dict.
    Two different owned window IDs are an ambiguous composite sample, never
    evidence for either page. Missing ownership permits a gap only during a
    transition. Missing ownership at both boundaries also requires exact 409.
    """
    _require(all(callable(f) for f in (read_pin, read_ui, idle, clock)), "invalid_callback")
    _require(type(package) is str and bool(package), "invalid_package")
    _require(type(allow_transition) is bool, "invalid_transition_flag")
    _check(deadline, clock)
    initial = read_pin(deadline)
    _check(deadline, clock)
    before = _owned_window(initial, idle, package, allow_transition)
    ui = read_ui(deadline, allow_transition)
    _check(deadline, clock)
    final = read_pin(deadline)
    _check(deadline, clock)
    after = _owned_window(final, idle, package, allow_transition)
    gap = ui == {"httpStatus": 409, "error": "home_ui_application_window_required"}
    _require(type(ui) is dict and (gap or ui.get("ok") is True), "invalid_ui_response")
    if before is None or after is None:
        _require(allow_transition, "owner_missing")
        _require(before is not None or after is not None or gap, "missing_owner_ui_mismatch")
        return None
    if before != after:
        _require(allow_transition, "owned_window_changed")
        return None
    if gap:
        _require(allow_transition, "unexpected_ui_gap")
        return None
    return ui, before


def wait_for_navigation(*, read_sample, prove, deadline, clock=time.monotonic,
                        sleep=time.sleep, poll_interval=0.1, settle_time=0.1):
    """Wait for two matching page proofs separated by the settling interval.

    read_sample(deadline, allow_transition) must return the above sample shape.
    prove(ui, window) returns an immutable, hashable public page token, or None
    for the still-visible admitted source page. After the first destination
    proof, missing ownership or a return to the source fails; it is never used
    to justify another input. Late callbacks cannot establish success.
    """
    _require(all(callable(f) for f in (read_sample, prove, clock, sleep)), "invalid_callback")
    for value in (poll_interval, settle_time):
        _require(type(value) in (int, float) and math.isfinite(value) and value > 0,
                 "invalid_interval")
    previous = None
    since = None
    destination_seen = False
    while True:
        _check(deadline, clock)
        sample = read_sample(deadline, not destination_seen)
        _check(deadline, clock)
        if sample is None:
            _require(not destination_seen, "destination_owner_lost")
            token = None
        else:
            _require(type(sample) is tuple and len(sample) == 2 and type(sample[0]) is dict
                     and type(sample[1]) is int and sample[1] >= 0, "invalid_sample")
            token = prove(*sample)
            _check(deadline, clock)
            if token is None:
                _require(not destination_seen, "destination_lost")
            else:
                try:
                    hash(token)
                except TypeError:
                    raise NavigationObservationError("invalid_proof") from None
                destination_seen = True
        now = _check(deadline, clock)
        if token is None:
            previous, since = None, None
        elif token == previous and since is not None and now - since + 1e-9 >= settle_time:
            return token
        elif token != previous:
            previous, since = token, now
        sleep(min(poll_interval, deadline - now))
