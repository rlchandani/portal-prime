# Source-folder video previews: decode the small frame directly

The active folder picker calls approvedSourcesThumbnail. Its video path previously called
MediaMetadataRetriever.getFrameAtTime(0), allocating a full-resolution video frame, then made a
second 512-edge bitmap and recycled the first. The existing two-slot decode limit bounds
concurrency, but two 4K ARGB frames alone can represent roughly 64 MiB before those resizes.
The unused square-thumbnail helper was not rewritten for this change.

Portal API 28/29 now uses getScaledFrameAtTime(0, OPTION_CLOSEST_SYNC, 512, 512), supported from
API 27. The media retriever preserves aspect ratio within that rectangle. The same source-preview
cache, cancellation check, IO placement and two decode slots remain. The shared helper retains
the compatibility resize for API 24-26 and releases its owned frame if resizing fails. A scaled
decode that fails or returns null never falls back to a full-size decode; the existing picker
continues its unavailable-preview behavior and the retriever is released by its caller.

## Verified results on Family Area

| Generated 4K preview measure | Previous frame call | Direct scaled call |
| --- | ---: | ---: |
| Returned bitmap dimensions | 3840 × 2160 | 512 × 288 |
| Returned bitmap allocation | 16,588,800 bytes (15.8203 MiB) | 294,912 bytes (0.2812 MiB) |
| Allocation decrease | — | 98.22% |

Two sequential native fixture requests produced the same allocation results. Each request
called the old full-frame operation once as a sequential control, recycled it, then invoked the
actual approvedSourcesThumbnail path twice. Both repeated preview requests returned the same
cached Bitmap. The generated file and only its preview-cache entry were removed; the owned
fixture bitmaps were recycled. No household image/video bytes, names, paths or URLs were exported.

The control and preview timing records are retained ([(1047, 584), (647, 618)]
ms, legacy/preview). They are ordered, include different setup/cache work and are not a statistical
latency comparison. No FPS, UI tap-latency, codec scratch-memory or whole-process peak-memory
percentage is claimed. This is a verified reduction of the returned preview bitmap allocation
for the generated 4K SDR fixture on the real Portal.

- Six JVM tests cover API selection, no large-frame fallback on null/exception, older-Android
  small-frame preservation, portrait scaling and frame cleanup on resize failure.
- The three strategy regressions failed against the previous full-frame path before implementation.
- Full debug and optimized benchmark builds passed: 1,809 tests / 209 suites,
  zero failures, errors or skips.
- 33 offline runner checks passed. Device scope is exactly two fixed diagnostic GETs, zero setting
  edits, zero collectors and at most one rollback. Cache/cleanup/ratio/aspect/owner assertions
  must all pass before candidate retention.
- The candidate was retained, Home restored and collector inactive. Complete settings and
  photo-source snapshots stayed unchanged. Living Area and phone: zero operations.

## Test-only native endpoint

The Fleet-token-authenticated /perf/source-preview route requires the existing opt-in functional
diagnostic build, Dev Mode, a worker thread and idle performance collectors. It accepts only GET
without query parameters; there is no caller-supplied file, URL or script. An atomic singleflight
prevents concurrent diagnostic codec work. The generated one-frame 3840×2160 H.264 asset is copied
to a temporary app-cache file and deleted after use. Responses contain only fixed-fixture scalar
metrics. Ordinary debug/release builds reject the endpoint before media work.

## Artifact

Candidate: `631a452bfc6b93a6f5bc2c8496a8af7c0369d35d26d2cb434084939ecb80d619` (25,445,414 bytes).
Matching bootstrap: `546e49e8cb6a6788268ca642f05f3bcf09cb7f7a9385df63993805b63aa99e69`.
Source: `fe6dc6af6613d525f0bab5c7ec40702b7364de9f708d2fb85e69580ff9718564`.
Postflight: `169d1fbd93310af79f7f9ea08270bb5fb7113c9f0553597414a4c3b3912e2771`.

Exact SDK/bundled/reference sets and DEX mapping passed: 8,658 methods /
2,573 classes, including 285 application methods /
75 application classes. Own OAT metadata reports speed-profile/install-dm;
individual method compilation is not proven. The obsolete six-name mapper gate is not the
admission gate. App source stayed frozen across both builds and the native trial.

Issue started 23:27:51 UTC on 2026-10-11; its deadline was 23:57:51 UTC.
Private build/admission/unit/native evidence is under the primary workspace's ignored
build/fleet/diagnostics/source-video-preview-2026-10-11/. The broader performance goal remains active.

Android reference (four-argument API added in API 27):
https://developer.android.com/reference/android/media/MediaMetadataRetriever#getScaledFrameAtTime(long,int,int,int)
