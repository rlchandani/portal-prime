# Portal Toolkit uptime validity

Toolkit now returns unavailable uptime for nonfinite or negative elapsed values.
The parser already promises `null` for invalid input, and the dashboard already
uses `null` for its unavailable label. The numeric guard runs before integer
conversion: the unchanged baseline converted `NaN` and `-0.50` to zero,
`Infinity` to `Long.MAX_VALUE`, and `-Infinity` to `Long.MIN_VALUE`.
These are generated parser inputs, not observed kernel output.

The correction adds one finite/nonnegative predicate to the existing parser.
Zero and positive fractions retain their existing whole-second conversion.
Tokenization, formatting, status transport, UI and persistence are unchanged.
Extreme finite values retain the existing conversion behavior; this change
introduces no duration cap or new accepted-input grammar.

The frozen seven-method regression calls the actual pure parser. With the
seven unchanged original cases, fresh baseline execution has **four assertion
failures and ten controls**, with zero errors/skips. The exact same 14 methods
then pass. The full gate executes **227 unique JVM tests / 38 suites**, all
passing with zero failures/errors/skips, and debug assembly succeeds. The
focused and full test tasks actually execute; these are fresh results.

- Baseline: 2026-11-03T20:20:52.025637+00:00–2026-11-03T20:20:59.015994+00:00, exit 1.
- Focused candidate: 2026-11-03T20:21:23.636826+00:00–2026-11-03T20:21:30.195773+00:00, exit 0.
- Full tests and assembly: 2026-11-03T20:21:30.198269+00:00–2026-11-03T20:21:36.166262+00:00, exit 0.

Local Toolkit commit: `d15912d09b90345f728735d9e5a5b0dbec9fd17f`, parent `685adb1cd358a7af794e053fa84f3deaaa06454c`.
Exactly the parser and new regression file change. The independent source/result
review passes 12/12, was frozen after execution, and was fully read and rehashed
before the source commit. The reviewer inspected archived results without
executing gates. Original Toolkit stays at its preceding clean checkout.
The existing CI test command discovers the new test; hosted execution is open.

Debug APK: **14007650 bytes**, SHA-256
`252f8a854342b0b5114cd8be724062797a881a3b88c95d68067678eb581af211`. It was built and hashed locally; no installation,
signing or native dashboard acceptance is claimed.

Profile application discovery found no supported new defect in its selected
actual caller path. Duplicate profile-store/editor and scanner observations
retain their original clocks. The earlier APK read candidate stays parked.

- Direct pure parser and existing formatter tests only. The active status reader and dashboard null/unavailable branch are source-reviewed; no live proc, ADB, Portal, phone, native UI or kernel-output acceptance.
- The guard preserves the existing conversion of finite nonnegative values. No finite upper bound, duration cap, whitespace/token grammar, idle-field validation or sign-text policy was added.
- Debug build and artifact hash only; no installation, signed release, version/dependency change, hosted CI, push or publication. Earlier phone, provider, approval and expired-issue holds remain.
- Profile application discovery is a bounded negative. Duplicate store/editor and scanner scopes retain their earlier clocks; the APK read candidate remains parked.

Original clock: **20:18:18–20:48:18 UTC, 3 November 2026**, including discovery,
with no reset of older scopes. Queue **115: 69 delivered, 35 verified,
3 approval, 7 parked, 1 queued**. All 114 prior objects/raw suffix and the
Weather default remain. Whole-product work stays active.

Structured proof: `mocks/portal-widget-gallery-20261102/evidence/toolkit-uptime-validity-verification.json`.
