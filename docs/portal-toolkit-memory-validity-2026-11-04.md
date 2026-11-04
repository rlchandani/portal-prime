# Toolkit RAM numeric validity

Toolkit now preserves the sign of the first memory-value token and checks that
kilobytes fit in `Long` bytes before multiplying by 1,024. Negative values,
digits embedded in error text and overflowing values are rejected before
conversion. Readings without a usable total or available value use the existing
unavailable-data path. These are generated inputs, not observed kernel failures.

The existing `MemAvailable` → `MemFree` fallback remains, including fallback
when Available cannot parse. Zero, unitless values, whitespace, the largest
representable value and available-memory values above total retain their
existing behavior. No device-memory cap, positive-total requirement, unit
grammar, dashboard design or persistence policy is added.

The separate Toolkit continuation starts from retained maintenance
`d15912d09b90345f728735d9e5a5b0dbec9fd17f`; source commit is
`205f2e56a173273fbde4afd9b03dd6ff4ae7b048`. Original Toolkit and the current
companion checkout remain unchanged; the companion's four preexisting
photo-transport changes are preserved exactly.

| Target | Implementation and evidence | Release / acceptance |
| --- | --- | --- |
| Related Toolkit parser | One token read and representability guard; 12 new actual-parser methods | Local source branch only |
| Frozen JVM regression | Original 17 methods: nine assertion failures and eight controls; same methods then all pass | Independently repeated directly against original and candidate compiled classes |
| Toolkit host gates | Clean pre-fixture baseline 227 tests / 38 suites; final 239 / 39, zero failures, errors or skips; debug assembly passes | Actual test task executes; existing CI discovers the new file |
| Dashboard and status reader | Existing active callers map null to unavailable RAM data | Source-reviewed only; no native or live `/proc/meminfo` acceptance |
| Portal and phone | Portal 1.133/code127 and phone delivery remain unchanged | No install, phone inspection/control, device or provider action |

The first setup attempt lacked `sdk.dir`; it is excluded from product regression
evidence. The existing local SDK was then configured and the untouched full
baseline passed in 15 seconds. The regression RED takes five seconds and the
final full test/build gate nine seconds. These are Gradle task durations, not
runtime performance measurements.

The local debug artifact is **13,901,710 bytes**, package `com.portalinstaller`,
version **1.4.0/code5**, SHA-256
`a76042cddcff791bac8cc45ed319192d780c22c2dfa0166a0cb55e3991af7c1d`.
Package/version are checked in the actual APK manifest. This is a build artifact;
there is no installation, publication or production-signing acceptance.

Earlier Alpaca fallback, MQTT, file-browser, scanner, profile and other held
observations keep their prior clocks. The unused HA room cap is unselected:
enforcing it would hide controls and introduce a selection policy. DockStore
has no current production caller. No fixture or correction is claimed for these
observations.

The original **06:36:36–07:06:36 UTC, 4 November 2026** window includes discovery,
setup, fixtures, implementation, gates, review, documentation and commit. It
does not restart earlier work. The whole-product goal remains active.

The queue now has **124 tasks: 75 delivered, 39 locally verified, 2 awaiting
approval, 7 parked and 1 queued**. All 123 prior objects and their 415,562-byte
raw suffix remain exact, including the Weather default and previous clocks.

Strict MkDocs passes. The new detail shows the source commit, both platform
labels and acceptance limits at 1,600 × 1,000 and 390 × 844, with no captured
console warnings or errors. Its mobile card is 343 pixels wide and was
explicitly scrolled into view. The full document measures 391 pixels at the
390-pixel viewport; this does not establish whole-page overflow or task-button
scroll acceptance. Historical detail acceptance is unchanged.

Only owned preview tabs, servers, documentation environment and generated site
are retired. The committed Toolkit worktree and regression evidence are retained.
SDK setup, preview-root, unavailable AX scroll and documentation/checker
assertions are setup corrections, excluded from the nine product RED assertions.

Structured proof:
`mocks/portal-widget-gallery-20261102/evidence/toolkit-memory-validity-verification.json`.
