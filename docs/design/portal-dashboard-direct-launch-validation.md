# Dashboard: direct browser dispatch verified on Family Area

The dashboard gateway previously queried PackageManager.getLaunchIntentForPackage on the
calling/UI thread before dispatching a URL. It now dispatches the saved ACTION_VIEW intent
directly to the existing org.chromium.chrome browser. Only ActivityNotFoundException permits
one generic URL-handler fallback. Security errors and other failures are not replayed.
The existing URL, flags, browser app/session and saved dashboard configuration are preserved.

This change removes one preflight PackageManager API call; it does not establish a measured
website-load speedup. Android documents direct URL dispatch at
https://developer.android.com/training/package-visibility/use-cases#open-urls.

## Verification

- Five production-gateway tests cover direct dispatch, absent-browser fallback, no fallback
  on security/runtime failure, and invalid URL rejection. Four failed before the source fix.
- Combined gate with the verified Sounds change: 1872 tests / 
  219 suites, with zero failures, errors or skipped tests.
  Debug, optimized benchmark and exact-candidate bootstrap builds passed.
- 159 offline native-runner tests passed, independently rerun after integration review.
- Two real dashboard dispatches passed through production RemoteRoutes and the pinned
  navigation gateway. Each confirmed the same Chrome foreground window twice and returned
  to a verified Immortal Home window. Exact installed APK, bundled/reference profiles and
  speed-profile compilation metadata were verified. Individual-method compilation is not
  proven. No page contents, URL, credentials or DOM were captured/exported.
- Candidate retained; source and complete configuration snapshots unchanged; Home restored;
  collector inactive. Zero settings edits, Living Area operations or phone operations.

| Handoff | HTTP navigation ACK | Chrome window verified | Home return verified |
| --- | --- | --- | --- |
| 1 | 101 ms | 1414 ms | 1234 ms |
| 2 | 79 ms | 935 ms | 1166 ms |

Times include HTTP transport and foreground polling. They are two functional observations,
not paired before/after measurements, tap-to-visible latency or page-load readiness.

The original failed dashboard trial remains preserved. This follow-up used the verified
Chime installation-return scope: strict pre-install Home plus exact freshly installed APK
proof, with one consumed post-install return. Chrome admission for that return is separate
from the dashboard handoff admission; normal navigation remains strict. Read-only window
observation uses the corrected stable-owner helper without replaying inputs.

The follow-up began at 02:38:09 UTC on 2026-10-12; successful device proof was confirmed at
02:55:31 UTC (17 minutes 22 seconds). The earlier blocked source pass had been parked;
this number describes the follow-up, not cumulative elapsed work across both passes.

The preceding 15-minute stability observation tested the Chime base, not this combined APK.
Its results are in portal-family-stability-2026-10-11.md. Broader browser page-load and
long-duration performance quality remain unproven; the full goal remains active.

Candidate APK SHA-256: `1e4ed99bb94e7054a0d3e957345661450df79250c1c1761aec548140737533e3`.
Source SHA-256: `43cea394252c6ad47f365a3e5c3d56228a6bda381d5eb54724512ea886122a50`.
Postflight SHA-256: `5344ca7684125ca85942b2c032e37161becbdc1b38e8d9d601ea93cd156a8879`.
Evidence: `~/portal-prime/build/fleet/diagnostics/dashboard-direct-launch-chime-base-2026-10-11`.
