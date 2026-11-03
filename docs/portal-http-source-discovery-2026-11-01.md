# Slideshow HTTP source discovery cancellation

**Delivered on Family Area:** closing a slideshow now cancels its startup HTTP
listing and disconnects owned transports off Main. Immich, WebDAV and shared-album
startup use the existing `PhotoSourceWork` owner instead of a plain executor.
Night-clock suspension retains the controller and its discovery result.

Source commit **fc0a2ada**, branch `codex/slideshow-http-discovery-20261101`.
Started **09:33:06 UTC**, hard limit **10:03:06 UTC**; delivery verified at
**09:46:44 UTC**, **13 minutes 38 seconds** after starting.

## Why this changed

Previously, `stopped` rejected late UI delivery and `shutdownNow()` interrupted the
controller's worker, but discovery connections were not registered to a cancellable
request. Thread interruption alone did not reliably release the Android HTTP read.

`SlideshowSourceDiscovery` now owns one coroutine job. Closing or replacing it
cancels that job; `PhotoSourceWork` owns the registered connections and performs
teardown on its cleanup worker. The controller still runs listing and cache setup
off Main and hands results through its existing paused callback queue. A closed
owner delivers neither obsolete media nor a failure fallback. Recoverable source
failure preserves the existing built-in-photo fallback; cancellation and fatal
errors retain their own semantics.

[Android's HttpURLConnection lifecycle documentation](https://developer.android.com/reference/java/net/HttpURLConnection)
requires disconnecting connections to release their resources. The native test
below establishes actual blocked-read behavior on the Portal; the documentation
alone does not establish cross-thread cancellation latency.

## Matched actual Android 10 comparison

The same final test APK exercised real `PhotoFrameController.start()` and `stop()`
on each app build. Read-only fixture preferences selected local Immich/WebDAV
servers; no real photo-source credentials or settings were changed. The server
stalled either before response headers or after one response-body byte.

| Stalled listing | Baseline disconnect | Fixed disconnect |
| --- | ---: | ---: |
| Immich headers | Not within 2,000 ms | **4 ms** |
| Immich body | Not within 2,000 ms | **3 ms** |
| WebDAV headers | Not within 2,000 ms | **3 ms** |
| WebDAV body | Not within 2,000 ms | **2 ms** |

The fixed probe passes **36/36 assertions**. The matched baseline passes **28/36**:
four disconnect checks fail, and their four conditional worker-availability checks
are not executed because disconnection failed. This does not claim the baseline's
shared worker was itself measured blocked; its listing used the other executor.

For every fixed case, the shared discovery worker accepted its next request before
fixture teardown. Controller/loader workers retired, native pixels were released,
remote media was not admitted after stop, and all fixture sockets/directories were
cleaned. The fixed probe took **549 ms**; baseline **8,396 ms** includes four
intentional two-second observation windows. These are cancellation-test durations,
not first-paint or slideshow FPS measurements. Stop rendezvous includes dispatch
onto Main and is not a standalone UI-latency benchmark.

## Build and delivery

- **3,697 unit tests / 391 suites**, zero failures, errors or skips; eight new tests.
- Debug, optimized performance and Android test APK builds pass.
- Review found no concrete blocker in the final ownership and controller wiring.
- Both matched native runs restored APK/test APK, all 97 settings, 22 source records,
  pin state, exact accessibility configuration and the original Dream. Android's
  system process stayed unchanged.
- Optimized non-debuggable APK **6f413f87…** is installed with the existing signing
  certificate. Deployment took **18.692 seconds** and preserved those same settings.
- Fresh installed-hash verification passes; no active instrumentation or performance
  collector/listener remains, and the original screensaver is active.

## Earlier checks and limits

An initial fixture used Android's default loopback address with an explicit IPv4
URL and never reached the adapters. It supplies no cancellation evidence; the
Portal was restored. A subsequent early valid probe saw three failed disconnects
and one Immich-body disconnect at 4 ms. Its pixel-release check did not yet await
the separate native loader worker. The final matched probe waits for that worker;
all cleanup assertions pass on both builds. The early variation is why this record
reports the matched pair and does not claim statistical latency percentiles.

A desktop-JDK real-body unit check failed to close its socket within four seconds.
That check is not reported as passing. JVM tests now cover the owner contract with
an explicitly noninterruptible transport; the actual Android gate remains the
real socket-closure acceptance. Every previous run was terminal and restored
before another began; an observation timeout alone did not trigger a restart.

This accepts startup discovery cancellation for the tested HTTP paths on Family
Area. Shared-album wiring was reviewed, but a live shared service was not tested.
Real NAS/DNS/TLS/internet outage behavior, optional camera cancellation, attached
window/GPU/FPS behavior and physical night brightness comfort remain unaccepted.
Living Area was not changed. Music provider/audio wiring remains deferred.
The broader goal remains incomplete.

[Sanitized verification receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-http-source-discovery/verification.json).
Private credentials, snapshots and rollback artifacts remain outside Git.
