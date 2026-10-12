# Dashboard launch: source-tested, device verification incomplete

The dashboard gateway now dispatches the saved URL directly to the existing Portal browser.
It falls back to Android URL handling only after `ActivityNotFoundException`, preserving the
same URL intent, browser application identity and existing login session. Security failures
and other launch failures are not replayed. No dashboard configuration is changed.

Android recommends this direct ACTION_VIEW dispatch pattern:
https://developer.android.com/training/package-visibility/use-cases#open-urls

## Evidence and limits

- Five gateway tests execute the production gateway with OS/storage boundaries mocked.
  Before the change, four of those five tests failed.
- Full local gate: 1,861 tests across 217 suites; zero failures, errors or skipped tests.
  Debug, optimized benchmark and bootstrap builds succeeded.
- Frozen source digest: `98cc320c1c343366610777cb6679a38e15260d5241d7eaa3aa0e983c5f94a7c8`.
- Family Area native trial stopped at its foreground-window safety guard: zero completed
  APK installations and zero native dashboard checks. This change is **not deployed** and
  no browser/page loading improvement is claimed.
- A subsequent read-only check confirmed the previous accepted APK is still installed,
  Chrome is foreground, no pin/cover operation is pending, and the collector is inactive.
- The candidate is preserved on `codex/portal-dashboard-direct-launch-pending` so other
  performance work can continue from the deployed source without bundling this unverified change.
- No Living Area or phone operation was performed.

Local proof files live under
`~/portal-prime/build/fleet/diagnostics/dashboard-direct-launch-2026-10-11`.
Build logs: `/private/tmp/portal-dashboard-direct-build.log` and
`/private/tmp/portal-dashboard-direct-bootstrap.log`.
