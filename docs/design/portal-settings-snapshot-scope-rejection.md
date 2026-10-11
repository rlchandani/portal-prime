# Settings snapshot-scope candidate: rejected

One bounded Family Area comparison tested moving launcher/screensaver snapshot
loading from every embedded page into the Appearance, Weather and Calendar row
component that consumes it. It preserved registry apply/reload, resume observers,
retained-pane ownership, navigation and row rendering.

The candidate was **not accepted**. The previously accepted Settings Baseline
Profile APK and original source are restored. No additional speed gain is claimed.
This preference-scope approach will not be repeated as a frame-performance fix.

## Evidence and decision

The installed control was the accepted Baseline Profile APK. Each block used two
warm-up fresh Settings pages and five measured fresh pages in a warm process.
Settings UI/pane work observers were disabled. All 15 captures completed and
all detail selections retained the same Settings window.

| Appearance opening | Control before | Candidate | Control after |
| --- | ---: | ---: | ---: |
| Median largest frame | 40.472 ms | 43.192 ms | 47.030 ms |
| Worst observed frame | 47.332 ms | 56.373 ms | 75.085 ms |

The candidate failed all three predeclared performance conditions: median at
least 10% better than both controls, worst frame within 10% of both controls,
and control median drift within 15%. These small matched samples do not prove
persistent slowdown or its cause. They provide no basis for promotion.

Removing unused preference lookups did not demonstrate the requested rendering
improvement. This comparison does not attribute the remaining delay to a
specific layout, text, framework or hardware operation. It measured largest
frame duration, not tap latency, delivered FPS or cold startup.

## Correctness and artifact checks

The full existing gate passed 1,728 tests in 198 suites, with zero failures,
errors or skips. Debug, non-debuggable benchmark and target-pinned compilation
bootstrap builds succeeded. A separate source review found no concrete blocker;
it did not replace native checks.

The candidate passed three native checks: mini-player toggle round trip,
Appearance reload after a remote edit while retained/inactive, and
Appearance/Wallpaper/Back navigation within the same Settings owner. Weather,
Calendar and every other setting were not exhaustively retested on this candidate.

Package and signing certificate matched. Non-DEX, non-profile, non-signature APK
contents were identical. `classes2.dex` and `classes3.dex` changed. Across the
APKs, resolved SDK method/class descriptors and flags were equal (34,922 methods,
5,389 classes); physical method indices/checksums need not match after code edits.
App profile entries were scoped HP methods: 1,285 control, 1,280 candidate,
with zero app class-preload entries.

For every native block and restoration, full bundled/reference profile sets and
DEX checksums matched within that APK. Exact installed APK and metadata plus
APK-specific mapped compiled output (`speed-profile`, `install-dm`) were verified.
Individual native compilation of every profiled method was not established.

Restoration verified unchanged configuration, Home restored and collector
inactive. Living Area operations: zero. Phone operations: zero.
The candidate source diff is preserved in ignored diagnostics and removed from
the authoritative worktree. The broader performance goal remains incomplete.

## Local record

Private evidence is under
`~/portal-prime/build/fleet/diagnostics/settings-scoped-snapshots-2026-10-11/`:
build logs, full unit gate, frozen source/input admission, profile inspection,
15 captures, three native checks, restoration proof and rejected candidate patch.
The complete native run took 262.91 seconds.

Accepted and restored APK SHA-256:
`d1635e3271943c8ed0d447d9ebe6da98692c5cb96d506b203980ade34026f2b4`.
Rejected APK SHA-256:
`111b2b896aaf48d288f0bf9a86edc3223e4bb569a927f978b9eb1ab1f2b907b6`.
Comparison/restoration postflight SHA-256:
`cb8b57d810bf930db84fee24c6af8775ef60f24e05b7bd383187624d79464bcc`.
No private setting values or registry credentials are committed.
