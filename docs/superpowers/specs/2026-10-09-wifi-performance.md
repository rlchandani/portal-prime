# Wi-Fi performance diagnostics and legacy analysis ownership

This increment supplies physical-device rendering measurements through the
persistent fleet channel and fixes obsolete legacy photo-analysis work. It is
part of the broader launcher, settings, slideshow, navigation, browser and phone
performance effort; it does not declare that quality goal complete.

## Frame capture

Extend the existing bearer-authenticated Fleet API with GET/POST /perf.
Starting capture requires existing developer mode, an explicit start action,
an integer duration of 10–900 seconds (default 60) and an optional phase from
all/home/apps/tools/settings/slideshow/launch/idle. Stop retains final results.
No capture survives app restart. Sessions expire by monotonic elapsed time.

Use Android Window FrameMetrics on API 24+, following Activity and owned Dream
windows. Track windows weakly, remove destroyed-window listeners, cap hosts at
128 and live windows at 24. Keep 0.25 ms histogram bins through 500 ms plus an
overflow bucket. Overflow percentiles report a lower bound rather than an exact
500 ms value. Include first-draw and callback-drop counts, timing components,
refresh-rate frame budget and aggregate memory only on explicit snapshot reads.

Inactive capture creates no frame listener or collector thread. Active callbacks
perform no JSON generation, file/network I/O, content capture or per-frame array
allocation. Expiry/stop remove listeners and asynchronously quit the collector.
Generation/closed checks reject late callbacks from a stopped/replaced session.

Window metrics describe host rendering, not compositor-delivered FPS, third-party
Chrome internals or time to interactive of arbitrary websites. Those remain
separate goals. No user settings or browser credentials are copied.

## Legacy analysis

Make legacy face warm-up cancellable and prevent overlapping sessions from
rewriting stale whole-file cache snapshots. Preserve existing fx/fy/pw inputs,
cache file compatibility and native slideshow analysis. Never join a worker or
close a network stream synchronously on the UI thread. ML Kit-owned pixels must
remain valid until its asynchronous task finishes.

## Acceptance

Meaningful JVM tests pin histogram bounds/overflow, request validation and
legacy worker/cache ownership. Build debug/test APKs and run the full unit gate.
Check timed capture, explicit stop, replacement and actual frame delivery on an
emulator before deployment. Then collect frames from the real Family Area
Portal over Wi-Fi, validate automatic expiry and measure launcher/settings/
slideshow samples. Save results with honest limits and preserve prior branch.
