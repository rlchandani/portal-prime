# Accessibility reconnect service preservation — 2026-11-03

When BarWatch is enabled but disconnected, the phone-remote enable action removes
its entry and rejoins after the existing two-second gap. The old callback replayed
the foreign-service list captured before that gap. A service added during the gap
could disappear; a removed service could return. The ordinary pairing call starts
the Fleet agent after requesting reconnect, and a newly started agent calls the
real install-confirm enabler, so that newly appended service could be lost.

The callback now reads the current secure enabled-service list and applies the
existing BarWatchServiceList policy. It writes the list only when it changes.
Foreign additions, removals, replacement order and duplicates survive. An existing
canonical BarWatch entry keeps its current raw list; mixed-case own duplicates
retain their existing repair policy. The immediate own-service drop, two-second
delay, master-flag writes and connected early return remain.

Ten frozen real-method JVM tests reproduce **seven org.junit.ComparisonFailure
assertions and three passing controls**, zero errors or skips, before production
changes. ComparisonFailure is an AssertionError subtype. One failure calls the
actual enableInstallConfirm boundary during the held callback gap. Literal lists
also cover late additions/removals, replacement order, canonical re-add, own-case
repair and overlapping callbacks. Controls retain connected no-churn, the own-only
or empty list, and unchanged foreign drop/rejoin, master flag and delay. All ten
pass unchanged. No production test hook was added.

Full gates pass **4,293 JVM tests / 445 suites**, zero failures,
errors or skips, plus debug and optimized builds, R8 and vital lint in **2m 14s**.

Actual debug Family checks improve 6/12 to 12/12: 6 corrected checks, 2 retained product controls and 4 retained phase or harness checks. Each variant invokes the actual reconnect method 8 times, install-confirm append 1 time and availability 1 time across 8 generated cases. Native product controls cover empty lists and unchanged foreign order.
Connected early return and overlapping callbacks are JVM checks only.

The native fixture calls the actual debug APK methods with a generated Context,
ContentResolver and in-memory IContentProvider proxy. These Context and provider
operations are real fixture-local calls, not zero operations. The generated in-memory provider handles 9 GET and 34 PUT calls on baseline, and 17 GET and 33 PUT calls on candidate (43 and 50 total calls). Each variant acquires it 1 time and records 0 releases. No
provider faults, unexpected provider calls or forbidden Context calls are observed.
Original secure-provider operations, household preference writes, real network
and service starts are zero. It does not invoke the pairing UI or install an app.

Both accepted native parents retire and scratch is removed without faults. The
process main Looper is prepared and is retired with its parent; process defaults
are not restored in place. Only normal cleanup is observed. The first actual
compiler succeeds with **3 Java 8 obsolescence warnings and a deprecation
note**. Its compile-only SDK shim is excluded from program inputs and absent from
the six fixture DEX class definitions. A root wrapper initially expected a missing
artifact-schema filename; that read-only admission failure occurred before any
helper or ADB execution, and its correction is separately retained. No excluded
compiler or native execution is counted as acceptance.

Native acceptance is **debug only**; the original SettingsGuard class and methods
are absent or inlined in the inspected optimized APKs, so zero optimized reconnect
calls are accepted and no keep rules were added. Family API29 is observed; API28,
actual OS Settings observer/rebinding timing, physical phone pairing/navigation,
automatic installation and external timeout/failure cleanup remain unverified.
The callback read and write are separate operations: a service change after its
fresh read but before its write remains outside this correction. An explicit
BarWatch disable during the gap still follows the existing requested reconnect
behavior. This fixes stale captured-list replay, not universal concurrent updates.

Exact signed optimized **1.120/code114** is installed and fully hashed in Family
Area and Living Area. SHA-256 `927fc4ec5966aad6e3e07258545007422cfa7292c9352dd6c581571153ed7972` (25,862,702 bytes),
package `com.immortal.launcher.debug`; signer matches the retained 1.119 rollback.
Both helpers exit zero and request the screensaver. The retained rollback is not
executed for this unit.

Complete captured screensaver, settings/profile, layout and source maps match
preflight; room name and Dev Mode remain. **Eleven preference states per room match: Family has ten byte-identical files and one unchanged absence; Living has seven byte-identical files and four unchanged absences. The news preference remains absent in both rooms.** Strict postflight passes
without an exception, household preference edit or restore. Family font,
accessibility, original Chrome task, resumed frame, awake display and installed
full SHA remain. The coordinator viewed Family's actual display:
Photo frame displays a photograph within a black border, with3:57 clock/date/cloud44degree weather at lower left and bug icon at lower right. Only current screensaver frame inspected. No actual reconnect, remote or installer appearance
is inferred. Living visual acceptance remains open.

Diagnostic and logcat lines=1500 requests return HTTP200. Actual outputs contain
**1,644 Family and 2,023 Living lines**, with zero fatal or ANR
markers. These are limited positive observations, not complete runtime health.
Original registry hashes match; only the owned copied registry is removed.
Public manifest remains **1.83/code77**; its guard rejects local 1.120/code114 with
expected exit 1. Native CLI, phone prohibition, HA, auth, push and publication
boundaries remain.

Original independent audit clock **11:43:07–12:13:07 UTC** includes discovery,
implementation, gates, delivery, documentation, cleanup and commit, without a
reset. Stories metadata/selection audit found no grounded fresh defect; known
bedtime narration findings and every earlier held or exhausted scope keep their
original clocks. The broader goal remains active. **96 tasks: 61 delivered,
25 locally verified, 3 approval, 6 parked and 1 queued**. All 95 prior task objects
and their complete raw suffix remain; Default Weather and boundaries remain
except the current room version. Documentation/rendering checks are recorded
separately in the structured proof: `mocks/portal-widget-gallery-20261102/evidence/accessibility-reconnect-services-verification.json`.
