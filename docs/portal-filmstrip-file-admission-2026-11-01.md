# Filmstrip file admission without whole-image buffering

**Delivered on Family Area:** native Filmstrip now inspects an encoded file
through one **8 KiB** seek-backed window before reading its full payload.
GIF, animated PNG/WebP and unsupported containers retain the existing fallback.
The renderer and its pixel allocation are unchanged.

The bounded issue started at **08:46:43 UTC**, with a **09:16:43 UTC**
hard limit. Source commit is **63d5fd4e**, on
`codex/filmstrip-file-admission-20261101`.

## Cause and correction

Previously, `nativeFilmstripFile` read the complete file into encoded bytes
before deciding whether its format was suitable for the single-frame decoder.
Even a GIF signature, which immediately selects fallback, incurred that work.

`FilmstripEncodedInput` lets the existing conservative PNG/WebP parser inspect
either an owned byte array or a file window. File inspection seeks over pixel
payloads, streams metadata checksums through the same window and preserves the
metadata, chunk-count and encoded-size limits. Interrupted scans release their
file handle and preserve the thread's interrupt flag.

Admitted static files still use the bounded decoder. It rechecks the exact bytes
after reading them, because an external file could change after preflight.
The change introduces no settings, per-frame callback or new renderer.

## Verification

| Gate | Result |
| --- | --- |
| Targeted red gate | **34 tests, two failures**: excessive GIF allocation and ignored interruption |
| Targeted green gate | **34/34**, zero failures, errors or skips |
| Full unit gate | **3,684 tests / 389 suites**, zero failures, errors or skips |
| Build gates | Debug, optimized performance and Android test APKs passed |
| Container parity | Existing PNG/WebP, animation, CRC, truncation and exact metadata-boundary cases run against byte arrays and files |
| File ownership | Inspected files remain caller-owned; their handles close on return |

The red file-overload scaffold used `readBytes()` to reproduce whole-file
admission; it is not the installed decoder's allocation result. The native
comparison below exercises the actual decoder in both APKs.

### Allocation and admission measurements

| Measurement | Before | After |
| --- | ---: | ---: |
| JVM calling-thread allocation: 16 MiB sparse GIF fixture | **16,778,976 bytes** | **10,024 bytes** |
| JVM calling-thread allocation: file above 32 MiB limit | — | **520 bytes** |
| Family Android 10 process-wide Java allocation delta | **50,567,776 bytes** | **0 bytes recorded** |
| Family decoder invocation elapsed time | **57.781874 ms** | **0.385625 ms** |
| Explicit native fallback signal | Pass | Pass |
| Owned fixture cleanup | Pass | Pass |

The Android check uses the same public `SlideshowDecoder.nativeFilmstripFile`
entry point, four tiny-file warmups and one measured invocation in each debug
APK. The measured large fixture contains a GIF signature followed by sparse
padding; it tests admission/fallback, not playable animation.

The ART counter is cumulative and process-wide. It includes concurrent app work
and measurement overhead, and may not update during a short call. Its recorded
zero is **not proof of zero allocation**. The JVM counter independently verifies
the bounded scanner allocation. These single paired observations establish this
path's removed buffering; they are not latency percentiles, peak heap, FPS or
whole-slideshow acceptance.

Both native runs restored the original optimized APK, test APK, all **97
settings**, **22 source records**, persistent pin state, accessibility services
and original Dream. Android's system process stayed unchanged, the observer
stopped and the performance collector was inactive.

An initial helper attempt stopped at its request-cache guard before probing.
It encountered an exact, previously owned missing-file diagnostic mistakenly
saved as request content. After that terminal attempt restored device state,
the helper was corrected to distinguish absent files from valid JSON. Only the
identified test artifact was cleared. No observation timeout triggered a restart.

## Family Area deployment

Optimized, non-debuggable APK **eafade20…** is installed with the existing
signing certificate. Deployment took **17.557 seconds**. Configuration,
sources, pin state and accessibility are preserved; the original screensaver
is running. A fresh installed-hash check passed, with **zero performance
listeners** and no active instrumentation.

See the [sanitized receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-filmstrip-file-admission/verification.json).
Private profiles, credentials and rollback APKs remain outside Git.

## Remaining limits

Animated GIF/WebP pixel and GPU residency remain open. Static images still
buffer their encoded payload for the existing decoder; the improvement is early
format rejection and bounded file inspection. Network byte-array admission is
unchanged. The native comparison uses debug APKs, not an optimized latency
benchmark.

Living Area was not touched. Natural-light brightness comfort remains pending
because the user cannot perform that physical check now. Music provider/audio
wiring stays deferred, and the broader goal remains incomplete.
