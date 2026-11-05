# Calendar recurrence end-limit fallback

Delivered optimized **1.198/code192** to both rooms, with complete installed APK byte/hash readback and one install attempt each.

A present recurrence UNTIL could be normalized from an impossible date/time or silently ignored when unreadable. The engine then emitted extra future occurrences despite its documented malformed-rule fallback. Present invalid limits now use that existing single-original-occurrence fallback. parseUntil validates with the existing date parser and rejects comma lists before its original conversion; valid end-of-day, UTC/local, locale and daylight-saving conversion remains unchanged.

Eleven frozen JVM methods through actual recurrence expansion and one generated full-parse case improved from **eight assertion failures/three controls to eleven passes**. They cover impossible civil dates, hour24/minute60/seconds61, suffix junk, incomplete/unreadable limits, valid inclusive date/UTC/local limits, existing spring-gap behavior and leap-second conversion. An initial11/9 run is excluded: its extra failure was an incorrect03:30 spring-gap oracle. That sole expected value was corrected to existing01:30 before final baseline and production edits. Final assertions stayed frozen thereafter.

Eight frozen literal vectors through the **actual debug APK recurrence helper on Family API29** improved from **five mismatches/three controls to eight passes**. This native subset uses generated finite rules and explicit epochs; it does not execute full parse, HTTP, live feeds, cache or UI. Class origins, identical Java/JAR/driver and process timezone/locale restoration are verified. No URL opens occurred; both owned scratch paths were removed. Host/native sets differ; native DST/leap controls are absent. Explicit comma rejection is source-reviewed without a dedicated frozen vector.

All **4,719 JVM tests/491 suites**, debug/optimized packaging, R8 and vital checks passed in **108.87 seconds**. Independent source review passed; root rehashed all25 bindings. All23 source assets and the signing certificate match1.197. Public manifest1.83 remains.

All43 Family/30 Living preference files, file sets and four complete settings/source/layout maps were preserved. Family font, accessibility, original Chromium activity, resumed frame and awake state were preserved; both normal-frame requests passed. Captured diagnostics are scoped samples. Copied registries were removed and owner registry hashes match.

[Generated delivery summary](portal-delivery-summary-1.198.md) and `mocks/portal-calendar-until-validity-20261105/verification.json` record evidence and limits. Optimized helper execution, API28, Living native and full calendar UI/provider/cache acceptance remain separate. Existing fallback ordering, COUNT/EXDATE/window rules and prior temporal scopes retain their boundaries.

Original start **2026-11-05 03:57:55 UTC**, discovery cap **04:02:55**, target **04:12:55**, hard limit **04:27:55**. The final corrected baseline overran discovery; initial reproduction preceded the cap. No clock reset; held scopes and the whole product goal remain open.
