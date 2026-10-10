# Synthetic operation progress rendering evidence

Checkout: `~/.codex/worktrees/portal-remote-progress-render/portal-prime`.
Local branch: `codex/portal-remote-progress-render`.
Baseline commit: `699f2c85e3495a6733c4fbfbb25c022f80df1b34`.

Operations-only polls now update Apps status text and disabled actions in place. Activity
reconciles cards by operation ID, changing only affected contents and retaining actions
when their capabilities are unchanged. The rendered JSON snapshots, four-second poll
cadence, focused-input deferral, generic read behavior, session guards, and manual refresh
remain intact. No additional timers or product assets are introduced.

Successful install/update transitions still read the visible Apps or Catalog endpoint
once. Identical metadata leaves the mounted cards/icons intact. Changed metadata takes
the existing full-render path once, showing fresh version/install/update state; a failed
metadata read still shows Apps unavailable, with manual refresh recovery. This deliberately
retains the full-render fallback for metadata changes, first render, and manual refresh.

## Source identities

SHA-256 of exact tested bytes:

| Path / source | SHA-256 |
| --- | --- |
| Baseline control-center.js from local commit 699f2c8 | `4b1df0a4859353caa8ffe366785b69cf1d755b078ce7b3ad9abec99bb12cce43` |
| app/src/main/assets/remote/control-center.js | `dd6a2b7ba6af7fec42254478eb3e8c93e57de09c7256bf7ed865df1232af0de1` |
| app/src/test/js/remote-operation-progress.test.mjs | `3a3e0588b32b3ace5680a7b76864b5ffde6d89c139a4e3438d4aff3d9e412c1c` |
| app/src/test/js/remote-app-poll-performance.test.mjs | `5a78cee1d2c7371ccee96b6d6b6e1aad9b7b82e117b63e2d410471e088ff6135` |
| scripts/tests/remote_mobile_poll_rendering.test.mjs | `8487ff9b5c0acc4899c94a0e40c2f10f689d40d0f389174ed3f55094b5657373` |
| Unchanged shared core.mjs | `d32a5d42344810a9ebce40945131e5158f3acb7d4e56aea81e797b73107928a2` |
| Unchanged shared control-center.css | `b0b12874e279bf98068e3b264372c68424dbe6521966fb9743cfaad5f3930e30` |
| Unchanged shared RemoteHtml.kt | `8352cec12055a0b2778e81c1c2e01b107705962fae8e780ba654e6e102302c6c` |

The prior `remote_mobile_poll_evidence.md` artifact is unchanged. Its historical baseline
was 4d512a5; the extended probe now compares the immediate pre-progress controller at
699f2c8 with this candidate, using identical current core/CSS/HTML.

## Commands and results

All commands ran in the checkout above. Git hooks were disabled; no GitHub, push, auth,
Gradle, hardware, real server, existing browser session, or household data was used.

```sh
node --test app/src/test/js/remote-operation-progress.test.mjs
```

Written before the product patch: RED, 9 tests, 2 passed / 7 failed, exit 1. Failures
proved redundant card/icon replacement on changed progress and identical completion,
non-keyed Activity updates/recovery, and two renders on changed completion metadata.
Metadata-error/manual recovery and stale-room metadata guards already passed. After the
patch: GREEN, 9 passed / 0 failed, exit 0.

```sh
node --test app/src/test/js/remote-app-poll-performance.test.mjs app/src/test/js/remote-operation-progress.test.mjs
```

GREEN: 21 passed / 0 failed, exit 0. Existing assertions requiring full-page replacement
for changed errors/deferred progress now assert retained nodes plus actual error/progress
state, disabled controls, and recovery. Manual/forced refresh and listener/navigation
cleanup coverage still passes.

```sh
node --check app/src/main/assets/remote/control-center.js
node --check scripts/tests/remote_mobile_poll_rendering.test.mjs
env -u IMMORTAL_REMOTE_CHROME -u IMMORTAL_REMOTE_SOURCE node --test app/src/test/js/*.test.mjs scripts/test_slideshow_sources.mjs scripts/test_slideshow_labels.mjs scripts/tests/*.test.mjs
```

Syntax checks exit 0. Full plain Node gate: 121 tests, 120 passed / 0 failed / 1 skipped,
exit 0. This is the existing 111-pass gate plus nine focused regressions. The optional
Chrome test skips with an explicit reason and does not load Playwright when opt-in is absent.

```sh
NODE_PATH=~/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules IMMORTAL_REMOTE_CHROME=1 IMMORTAL_REMOTE_SOURCE=baseline node --test scripts/tests/remote_mobile_poll_rendering.test.mjs
NODE_PATH=~/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules IMMORTAL_REMOTE_CHROME=1 IMMORTAL_REMOTE_SOURCE=candidate node --test scripts/tests/remote_mobile_poll_rendering.test.mjs
```

Baseline: RED, 0 passed / 1 failed, exit 1 at `changed_operations_no_icon_reads` (24 vs 0).
Candidate: GREEN, 1 passed / 0 failed, exit 0. Both emitted bounded fixed-label numeric and
boolean reports, after exercising all fixture phases; no DOM dumps or screenshots.

```sh
git -c core.hooksPath=/dev/null diff --check
```

Exit 0, no whitespace errors.

## Native Chrome work counts

Chrome `155.0.8059.40`, bundled Playwright `1.62.1`, fresh isolated headless profile.
The 402 x 874 viewport uses DPR 1, mobile/touch emulation, light theme, reduced motion,
blocked service workers, and a controlled application clock. Each poll advances exactly
4000 ms; no early read occurs at 3999 ms. Native fetch/body consumption, DOM, layout, and
PNG decoding settle independently through bounded condition checks. These are work counts,
not timing measurements.

All HTTP(S) requests are intercepted. Only fixture-origin GETs and enumerated generated
routes are fulfilled; unknown requests abort. WebSockets are closed and DNS/background
networking are disabled. Both runs observed unknown requests = 0, blocked sockets = 0,
page errors = 0. Twenty-four synthetic installed/catalog apps and twenty-four Activity
operations are used. Every app icon decodes a generated 74-byte 2 x 2 PNG, with no external
images, credentials, photos, or real API calls.

Counts per phase (baseline -> candidate):

| Phase | Created elements | Registered element listeners | Page replacements | Icon fetches | Icon-container replacements | Metadata reads |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Three unchanged Apps polls | 0 -> 0 | 0 -> 0 | 0 -> 0 | 0 -> 0 | 0 -> 0 | 0 -> 0 |
| New pending install, after one focused-input deferral | 299 -> 1 | 57 -> 0 | 1 -> 0 | 24 -> 0 | 24 -> 0 | 0 -> 0 |
| Changed running result | 299 -> 0 | 57 -> 0 | 1 -> 0 | 24 -> 0 | 24 -> 0 | 0 -> 0 |
| First Apps operations error | 299 -> 0 | 57 -> 0 | 1 -> 0 | 24 -> 0 | 24 -> 0 | 0 -> 0 |
| Apps operations recovery | 299 -> 0 | 57 -> 0 | 1 -> 0 | 24 -> 0 | 24 -> 0 | 0 -> 0 |
| Install completion, identical catalog metadata | 574 -> 0 | 114 -> 0 | 2 -> 0 | 28 -> 0 | 24 -> 0 | 1 -> 1 |
| Install completion, changed catalog metadata | 574 -> 299 | 114 -> 57 | 2 -> 1 | 28 -> 24 | 24 -> 24 | 1 -> 1 |
| Two changed Activity records, 24 total | 219 -> 18 | 7 -> 0 | 1 -> 0 | 0 -> 0 | 0 -> 0 | 0 -> 0 |
| First Activity error | 25 -> 4 | 7 -> 1 | 1 -> 0 | 0 -> 0 | 0 -> 0 | 0 -> 0 |
| Activity recovery | 219 -> 0 | 7 -> 0 | 1 -> 0 | 0 -> 0 | 0 -> 0 | 0 -> 0 |

Candidate Apps list/card/icon/search identities remain stable on pending, progress,
operations error/recovery, and identical-metadata completion. Status text actually changes,
the pending Update control disables, and completion shows Finished. Changed metadata shows
version 2.0 and a disabled Up to date action. Search draft is retained throughout. Native
focus/scroll are retained during running progress. Activity keeps all keyed outer cards,
updates exactly two card contents without replacing its list, retains the focused action
while its result changes, and preserves key order/focus/scroll through reorder. Error UI
appears and later recovery reconnects the exact same healthy list/cards (baseline false,
candidate true).

Three repeated same-error polls for Apps and Activity each perform three operations reads
and zero element/listener/page/icon work in both variants.

## Mobile bounds and limits

Installed, Catalog, and changed-metadata completion layouts each have 24 app rows. Row
bounds are left 38 / right 364 within width 402, with document width 402 and no horizontal
overflow. Every child fits its row. All 24 Installed controls and 48 Catalog/completion
controls are unclipped and pass center hit testing after scrolling into view. Minimum
control bounds are 74.0625 x 48 CSS pixels. Twenty-four native icons decode successfully.
These bounds are identical for baseline and candidate.

Coverage includes generated progress/failure/removal, keyed insertion/removal/reorder,
focused-input deferral, identical/changed completion metadata, metadata failure/manual
recovery, late metadata after room switching, ordinary/manual/forced refresh, retained app
listeners, and navigation cleanup. Existing broader remote tests still pass.

Changed metadata deliberately retains a full-render fallback: that phase replaces app cards
and icons and may lose focus on an Open button; search draft remains intact. Activity's
error replaces the visible list with error UI, so a removed action cannot retain focus and
the shorter page may clamp scroll. Recovery retains the healthy list/card identities.
No claim is made that these transitions preserve unavailable controls or arbitrary scroll
positions. Partial operation-card contents are still rebuilt for changed Activity records.

This is desktop headless Chrome with synthetic content and controlled time. It does not
establish Android-phone FPS, INP, touch latency, physical mobile acceptance, device install
behavior, or hardware performance. No Gradle or hardware validation was performed here.
