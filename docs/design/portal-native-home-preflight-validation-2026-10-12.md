# Native Home preflight validation — issue 71

This is a local verification-tool correction, not an Android app feature or performance gain.
The original issue window is 2026-10-12 21:03:11Z–21:33:11Z; it was not reset.

## Problem and change

The previous initial-Home guard required DREAMING when no application window was present.
Family Area instead reported INTERACTIVE and the exact owned-root error
`home_ui_application_window_required`. RemoteInput checks the root package before emitting
that error. Therefore it can establish an Immortal-owned non-application root without treating
presence as a window-ownership signal. Foreign roots, absent roots, OFF state, pinned or
covered surfaces, and busy collectors are rejected.

`portal_home_preflight.py` reads two fresh matching source proofs. An already proved Home
requires no action. Otherwise it consumes one attempt before calling the existing explicit
Immortal Home route, then requires two stable owned-Home observations. All callbacks share
one finite absolute deadline. Durable exclusive claims and bounded transport remain the
caller’s responsibilities. Coordinate input remains unavailable until actual Home is proved.

## Verification

- Independently reviewed helper and 16 focused tests: pass.
- Full local Portal tooling suite: 130 tests, zero failures/errors.
- Independently reviewed fresh native wrapper and eight offline transport tests: pass.
- Family Area: one initial owned-root Home normalization and one Home → Settings → original
  Home cycle, pass. Five distinct POST operations, no retries, zero installs or configuration
  writes. The accepted APK, source fingerprint, original configuration, Home, and idle
  collector/pin cleanup state were verified after the cycle.
- No Living Area or phone operation, no GitHub action, and no parked candidate reopened.

## Limits

App sources and the accepted APK are unchanged. No physical UI latency, frame-time, FPS,
website-speed or Android heap gain is claimed. One functional cycle does not establish
navigation endurance. Issue 70 and other parked candidates remain parked.
