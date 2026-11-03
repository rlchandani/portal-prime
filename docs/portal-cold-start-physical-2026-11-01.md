# Family Area cold slideshow startup

**Status: observed on hardware; startup performance remains open.** This pass
made no production rendering change. Family Area still has optimized APK
`199aa8a1…`, built from product source `cc8bc9de`.

The actual Android 10 / API 29 Portal+ uses local-folder Shuffle, photo motion,
30-second intervals and disabled persistent media caching. The first valid
fresh-process recording shows the native clock over a blank photo area before
the photograph appears.

| Observation | Result | Scope |
| --- | ---: | --- |
| Blank photo area after the native Dream clock becomes visible | approximately **3.42 s** | Screen recording inspected at 12 samples/s; approximately ±0.17 s endpoint uncertainty |
| Dream attachment to the engine's ready log | **3,490 ms** | Device monotonic log; readiness is not first visible pixels |
| First WebView construction | **422.994 ms** | Separate first-renderer run after a fresh Home process, with diagnostic sampling |
| Metadata preparation | **6.815 ms** | Same separate run; off Main |
| WebView admission | **464.397 ms** | Includes construction; do not add overlapping spans |
| Largest Dream Window callback in the sampled run | **570.149 ms** | 18 callbacks, including first draws; not panel FPS or a steady-state benchmark |
| Restoration | **97 settings / 22 source fields exact** | Each successful workflow restores settings, accessibility-service list, Dream and capture ownership |

The video and renderer run are different workloads. Their numbers are not a
before/after comparison and do not establish a new speed improvement. OS and
disk caches were not cleared. The recording affects workload, and the sampled
run adds profiling overhead. The native first-photo observation therefore
establishes an outlier to address, not complete cold-start acceptance.

## Firmware failure and safer measurement

Two initial workflows force-stopped Immortal with its accessibility services
still enabled. On this device that produced a fatal exception in Android's
**system process**:

```text
IllegalArgumentException: Service not registered
AccessibilityServiceConnection.onNullBinding
AccessibilityServiceConnection.unbindLocked
```

The resulting system restart interrupted ADB and caused `DeadSystemException`
in several apps. These attempts are invalid startup observations and are
retained as failures. The first attempt's initial cleanup receipt failed;
subsequent USB recovery independently verified the original settings, sources,
APK, Dream and inactive collector.

The successful workflow saved the exact enabled-accessibility-service list,
temporarily removed only Immortal's two enabled components, and verified that
change before stopping the app. Vendor services remained present. Normal app
startup can re-enable Immortal's components; cleanup restores the exact saved
list and verifies that the system-process PID did not change. This is a
temporary measurement procedure, not an installed firmware modification.
Do not repeat the unsafe force-stop method on this Portal.

## Tap-return qualification

A subsequent tap recording began on the automatically entered night clock,
rather than the intended photo surface. Visually, it returned to Home and
remained there in the reviewed samples. Its focused-window assertion timed
out, and its receipt remains **failed**; visual review does not turn that
receipt into a passing cold-photo tap test. The original Dream, settings,
sources and inactive collector were restored.

The earlier passing [Dream brightness check](portal-dream-brightness-2026-10-31.md)
and [pinned-overlay check](portal-pinned-night-physical-2026-10-31.md) retain their
own stated scopes.

## Decision and remaining work

This investigation was parked within its original 30-minute budget. It found
a real blank-photo interval and first-use WebView work, but did not identify
a justified production fix to complete and validate in that window. The
already parked background-WebView-startup experiment was not reopened.
Cold Shuffle startup remains incomplete; warmed Filmstrip and renderer-ready
callbacks do not close it.

The [sanitized receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-cold-start/verification.json)
contains the scalar observations and failed-attempt qualifications. Recordings,
screenshots of household photographs, complete settings and credentials stay
outside Git. The broader performance/night/player goal remains incomplete.
