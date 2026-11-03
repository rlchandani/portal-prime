# Local photo-folder traversal — 3 November 2026

Optimized **1.90/code84** is installed in Family Area and Living Area. Local photo
enumeration now visits each canonical directory once and limits traversal to
**20,000 entries**, including directories and non-media files. This prevents
directory aliases or parent links from repeatedly returning the same photos.
The existing 2,000-media default and smaller Home/wallpaper limits remain.

The media loop stops when either limit is reached. Previously its `forEach`
continued walking the remaining children after collecting enough media. The
summary helper also counts directories toward its existing scan budget, skips
repeated directories and checks cancellation. That helper currently has no
production caller and is removed from the optimized APK.

## Verification

| Check | Before | After |
|---|---:|---:|
| Initial real-filesystem host regressions | 10 cases, 5 failures, 0 errors | 14 cases passed, including four added budget/control cases |
| Complete JVM suite | — | 4,014 passed, 419 suites, 0 failures/errors/skips |
| Debug and optimized builds | — | Passed; R8/vital lint passed; combined run 117 seconds |
| Family Android scanner cases | 4 of 7 passed | 7 of 7 passed, 0 invocation errors |
| Directory alias containing one generated photo | 2 returned items | 1 returned item, 1 canonical path |
| Parent/self links containing one generated photo | 32 returned items, all returned paths exceeded Android's canonicalization limit | 1 returned item, 1 canonical path, 0 canonical failures |
| 20,001 non-media entries before a generated photo | 1 returned item | 0; traversal stops at the new budget |
| Ordinary images | 2 items | 2 items |
| Ordinary images with videos enabled | 3 items | 3 items |
| Explicitly chosen Screenshots root | 1 item | 1 item |
| Nested junk directories | 1 retained item | 1 retained item |

The baseline used the installed optimized 1.89 APK. Candidate acceptance loaded
the exact optimized 1.90 APK on Family's Android 10 hardware through a separate
shell `DexClassLoader` process. Classloader provenance, APK hash, method
descriptor and result fields were checked. The later installed APK hash matches
that accepted artifact in both rooms.

R8 merged the default-argument entry point into the candidate method. Its second
integer is a default-argument mask; the 20,000-entry budget is constant in the
optimized body. Actual disassembly established those bindings before invocation.
No fallback implementation was used.

An initial baseline verifier failed while resolving cyclic returned paths.
The corrected verifier records the returned count and canonical failures
separately. Those 32 failures do not mean the fixture contained zero physical
photos. The generated cycle contains exactly one media file.

## Preservation and limits

Both complete settings profiles, layouts, photo sources and screensaver
configurations match their original baselines. Names and dev mode are unchanged.
Family's font scale and enabled accessibility services match; its original Chrome
task remains. The ordinary manual photo frame is foreground and visually checked.
Living's return was acknowledged, but its visual presentation was not inspected.

The native fixture used extension-classified generated files under three owned
shell scratch directories. It did not decode/play them, change the selected
source or exercise application-UID storage permissions. Those three directories
and temporary token registry copies were removed; both workers closed.

The traversal cap can omit media beyond the scanned portion. It bounds entry
processing, not a single `listFiles()` allocation or directory sorting. No FPS,
latency improvement, complete source/UI acceptance, summary native acceptance or
whole-product performance claim is made. No new permission, Android setting or
provider/account configuration is required.

Package: `com.immortal.launcher.debug`. APK bytes: **25,861,122**.
SHA-256: `3697c00ae84c17ff8060f37d3c0f25e3f81b6e7a1846320ff295dcc96b91a285`.
The signer matches the preserved 1.89 rollback artifact. This is a local preview;
publication metadata remains unchanged.

Original unit clock: **00:37:12–01:07:12 UTC**, including discovery, deployment,
cleanup and commit. Other exhausted investigations retain their original limits.

Structured receipt:
`mocks/portal-widget-gallery-20261102/evidence/local-folder-traversal-verification.json`.
