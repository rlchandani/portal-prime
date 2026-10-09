# Accessibility resource cleanup and navigation validation — 9 October 2026

## Change and verification

`RemoteInput.typeText` previously retained its acquired accessibility root and focused node. It now releases each distinct node exactly once after the edit, including early returns and failures. `BarWatchService` also releases every acquired accessibility window after status-bar inspection, including items skipped by its short-circuit search. Android 9/10 requires explicit recycling of these pooled framework objects. Text editing and status-bar detection behavior remain unchanged.

The window regression reproduced four failures before the fix; the text-node regression reproduced 22 failures across 28 cases before its fix. After implementation, all 914 JVM tests passed with zero failures, errors or skips, and the debug APK built successfully. Commit `cdaf954` was installed on Family Area and Living Area; each installed APK SHA-256 matched the local build.

## Physical finite navigation run

The two rooms each completed 30 cycles over approximately five minutes. Each cycle exercised Home, Apps, Browser launch, system Back, Home, a non-mutating empty remote-text check, slideshow preview, Apps and Home. Each room processed 270 successful action requests: 240 navigation commands plus 30 text checks. No request failed. Full slideshow settings snapshots matched before and after the run. The diagnostic collector stopped, removed all listeners and ended its worker thread. Both rooms returned to the owned slideshow preview.

Acceptance of a command is not proof that the destination finished rendering. The slideshow entry in this run is the owned preview Activity, not a complete DreamService lifecycle test. Browser content is in a different process and is not measured by Immortal Window metrics. No household images, credentials or raw browser content were collected.

## Remaining rendering cost

Zero collector callback drops does not mean zero dropped display frames. The host measurements still show substantial over-budget work and do not establish an improvement over the prior build. This test has no controlled pre-fix comparison. Percentiles include first-draw frames; the steady over-budget counts below exclude them. Delivered compositor FPS is not measured.

| Room | Host | p95 total duration | Steady frames over 16.67 ms |
|---|---|---:|---:|
| Family Area | HomeActivity | 116 ms | 631 / 1487 |
| Family Area | PhotoFramePreviewActivity | 127.75 ms | 1238 / 2101 |
| Living Area | HomeActivity | 107.75 ms | 460 / 1446 |
| Living Area | PhotoFramePreviewActivity | 110 ms | 1129 / 1801 |

Home has multi-second maxima during the mixed workflow, including queue/layout cost. Preview has additional renderer command cost. The mixed run cannot attribute those stalls to one transition.

## Retained Home/Apps isolation

A separate 60-second run completed 20 Home/Apps cycles in each room, after a two-second warm-up. It used the same installed resource-cleanup build. Both captures stopped explicitly with zero active listeners and no worker left running; both rooms returned to slideshow preview. No API request failed.

| Room | Frames | Steady frames over 16.67 ms | p50 | p95 | p99 | Maximum |
|---|---:|---:|---:|---:|---:|---:|
| Family Area | 518 | 144 / 517 | 13.25 ms | 91.75 ms | 111.75 ms | 133.05 ms |
| Living Area | 518 | 131 / 517 | 12.5 ms | 71.5 ms | 89 ms | 110.27 ms |

The multi-second mixed-workflow stalls did not recur in this isolated run, but tab transitions still produced over-budget host frames. Mean layout cost was 0.12/0.15 ms and mean animation cost was 10.45/8.87 ms (Family/Living). This is an isolation baseline, not evidence of a speedup. Remote tab requests still used an Activity intent in this build; a foreground-only retained-state dispatcher is being evaluated against the same finite test.

## Broader work still required

The performance objective remains active. Physical Settings transition readiness, rendered browser/website load time, delivered slideshow animation quality, widget paging and direct touch latency still need stronger evidence. The visible physical Pixel Photos workflow remains pending secure unlock. Browser control-center parity and Android multi-Portal settings/photo uploads are also being implemented under the user’s subsequent requests.

Private bounded measurements and configuration snapshots remain under the primary checkout’s ignored `build/fleet/diagnostics/navigation-performance-2026-10-09/`.
