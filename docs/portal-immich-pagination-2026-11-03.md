# Immich pagination resilience — 3 November 2026

Both rooms contain the exact optimized **1.92/code86** preview APK. Unexpected
repeated or backward page continuations now stop discovery while retaining
collected photos. Duplicate playable asset IDs no longer consume the photo cap;
the first occurrence keeps its place. Advancing replies have a separate
100-request limit.

This is client resilience established with generated responses. No failure on
the user's Immich NAS was observed, and no account or saved photo source was
changed.

## Implementation and research

`ImmichSource.searchAssets` owns one seen-ID set for the complete search, counts
POST attempts and accepts only a continuation greater than the current page.
`assetsFrom` filters unknown types and excluded videos before inserting an ID.
Images and included videos share the unique-media cap. Existing page size,
album filter, authentication, cancellation checkpoints and malformed/empty
continuation behavior remain.

The reviewed Immich v3.0.1
[search service](https://raw.githubusercontent.com/immich-app/immich/v3.0.1/server/src/services/search.service.ts)
returns the next page as a string or null. Its
[request DTO](https://raw.githubusercontent.com/immich-app/immich/v3.0.1/server/src/dtos/search.dto.ts)
accepts positive integer pages and a size up to 1,000. A public
[repeated-page report](https://github.com/immich-app/immich/issues/31311)
was closed after correcting a missing `Content-Type` header. Immortal already
sends that header; this change does not claim to fix that report's cause.

## Verification

- Original code: 12 HTTP test methods, **9 failures, 0 errors**.
- Candidate: all 12 pass; full **4,038 JVM methods in 421 suites**, with zero
  failures, errors or skips.
- Debug and optimized builds, R8 and vital lint passed in the combined
  **152-second** build.
- Independent static review found no actionable pagination blocker.
- Family Area Android 10 loaded each actual optimized APK in a separate
  shell `DexClassLoader`. The candidate was byte-identical to the later installed
  APK. Installed 1.91 remained unchanged during both fixtures.

Combined media-output and exact-request-plan acceptance improved **3/9 → 9/9**.
Media output alone improved **4/9 → 9/9**; the unsupported-only case returned an
empty list in both versions but failed the original request limit.

| Generated case | Original entries / POSTs | Candidate entries / POSTs |
| --- | ---: | ---: |
| Normal two pages | 2 / 2 | 2 / 2 |
| Repeated first page, cap 4 | 4 / 4 | 1 / 1 |
| Repeated second page, cap 4 | 4 / 4 | 1 / 2 |
| Backward continuation | 4 / 4 | 2 / 2 |
| Duplicate rows, cap 2 | 2 / 1 | 2 / 1 |
| Overlapping pages, cap 4 | 4 / 2 | 4 / 3 |
| Videos excluded | 1 / 1 | 1 / 1 |
| Videos included | 2 / 1 | 2 / 1 |
| Unsupported-only advancing pages | 0 / 101 | 0 / 100 |

The duplicate-row case changes the actual IDs from `a,a` to `a,b`. The overlap
case changes `a,b,b,c` to `a,b,c,d`; its extra request intentionally fills the
unique cap. Fixture totals are **120 → 113 POSTs**, not a general NAS speedup.
The original unsupported-only fixture empties page 101 to terminate safely;
an infinite run was not measured.

## Delivery and preservation

Package: `com.immortal.launcher.debug`; APK size: **25,861,122 bytes**.

SHA-256:
`4ddddddef3feeafaba29f16a245b77cf9cce24620098ef5b8b7754a7b8c294d1`.

Signer SHA-256:
`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`.

Both full installed APK hashes and 1.92/code86 readbacks match. Complete
screensaver, settings profile, layout and source responses match each room's
baseline; names and developer mode are unchanged. Family font and accessibility
settings, the original Chrome activity/task and normal manual photo-frame
foreground were retained. Family's final private screenshot was visually
inspected. Living's display was not visually inspected.

Living's original command session handle became unavailable after the success
log. A separate fresh installed-APK hash and complete configuration readback
confirm installation; no duplicate deployment was performed.

Both owned Android scratch roots were removed and absence checked. The owned
fixture server reached terminal shutdown, workers were closed and private token
registry copies were removed. Evidence and personal screenshots remain private.
**No Pixel command was sent after the user's prohibition.** Phone cleanup and
physical acceptance remain deferred until explicit authorization.

## Limits and continuation

The 100 limit bounds requests, not total wall time, response memory or decoder
work. It may omit media beyond that budget. This unit retains the existing
legacy `page`/`nextPage` API; it does not implement cursor migration.

Generated authentication and metadata establish reader behavior, not real NAS
compatibility, app-UID networking, image downloads/decoding, cold slideshow
startup, frame rate or whole-product performance. Source settings, phone/web
interfaces and hosted publication metadata were unchanged.

Original issue clock: **01:27:16–01:57:16 UTC**, including discovery, build,
verification, delivery, cleanup and commit. The whole-product goal remains
active; exhausted issue clocks and outstanding design approvals remain intact.

Machine-readable public evidence:
[`immich-pagination-verification.json`](../mocks/portal-widget-gallery-20261102/evidence/immich-pagination-verification.json).
