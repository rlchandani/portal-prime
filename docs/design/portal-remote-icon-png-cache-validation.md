# Remote app-icon PNG reuse

Every remote app-icon/PWA-icon request previously resolved a package drawable, rasterized it and PNG-compressed it again. The provider now reuses encoded bytes and shares same-key concurrent encodes with the existing SingleFlightValueCache. Default bounds are 32 entries, 64 KiB per retained value and 30 seconds from completion: at most 2 MiB of retained payload. No drawables, bitmaps, activity Contexts, or PackageInfo objects are retained. Defensive copies isolate encoder buffers and returned byte arrays.

Keys separate owner package, target package, pixel size and the complete resource configuration string. Protected package added/removed/changed/replaced broadcasts clear the cache without doing rendering on the UI thread. Failed receiver registration preserves uncached behavior. Failed/null/empty/oversized results are not retained. A resource-configuration recheck after rendering prevents publishing pixels into the wrong configuration key; a failing recheck also disables retention. Old in-flight renders cannot republish after invalidation. The rasterizer, dimensions, PNG compression, bitmap ownership, request routes/auth and app-list/launch/Dream behavior are unchanged.

## Evidence

The original uncached bridge failed 12 of 14 behavioral cases; the two expected controls passed. The final implementation passes all 14. Controlled workloads reduce 20 sequential warm encodes to one and eight concurrent same-key encodes to one. Distinct keys remain concurrent. Package/size/owner/resource isolation, completion-based expiry, invalidation, old-flight replacement, failures/null/empty/oversized results, LRU bounds, byte-array ownership, receiver fallback and resource changes during rendering pass.

The complete clean Android gate passes 1952 tests in 227 suites, preserving all 1938 prior testcase identities. Debug and signed optimized benchmark builds succeed; all 138 browser tests and 219 offline guards pass. Independent review is CLEAR. Signed bootstrap and candidate are separately archived and verified against the frozen source, signature, exact installer target and complete bundled/reference profiles (8667 methods, 2576 classes, 285 app methods, 75 app classes).

One Family-only native trial completed with two installations, Home → Apps → Home in the same HomeActivity window, three verified destinations, initial/final Home, exact installed candidate, served assets, profiles, source and unchanged configuration. One admitted owned Immortal Dream normalization; no rollback. Zero Living/phone/settings/note/audio/collector operations. Specific compiled app methods and GPU/FPS improvements are not established.

Six public PNG GETs before and six after verify exact bytes, image/png, signature/IHDR dimensions and stable checksums, without credentials or UI actions:

| Variant | Bytes | Before observations (ms) | After observations (ms) |
| --- | ---: | --- | --- |
| Self 192 px | 12270 | 353.923, 34.721 | 63.133, 13.756 |
| Self 512 px | 49420 | 118.697, 141.731 | 103.714, 17.138 |
| Package 144 px | 7945 | 26.923, 26.814 | 33.699, 9.782 |

These are uncontrolled Wi-Fi observations, not isolated encoder timing or a general latency guarantee. The second request benefits from a warm cache in the candidate. No Android heap, phone, website-loading or universal frame-rate claim. The separate bounded three-minute HTTP availability monitor completed 180/180 exact responses with zero failures over 180.010 seconds: observed median 13.357 ms, p95 19.376 ms, p99 21.593 ms, maximum 22.780 ms. This is availability/transport evidence; it does not isolate encoder, UI or Android allocation gains. Final Home was verified at native completion, not throughout the idle monitor.

Candidate APK: 25448482 bytes / `a804c2450571adf48dbc5b90123464ffe05fee936be293405ee8d20aa10ffb7c`. Bootstrap: 25448482 bytes / `2db29e9e3252666e449d2f8db91d96f89c78fe85bd526b66811906c0d91ac0b8`. App source: `af7d33b4a6a068504aba6031a747fa60bf124ace9f024aa46dfc80e11f115072`, 870 files. Served JavaScript/core/CSS remain unchanged from accepted47.

## Scope

Family Area only; Living Area and phone frozen. Original clock 2026-10-12T13:38:29Z–14:08:29Z, no reset; native starts by 14:00:29Z. One two-install trial and at most one accepted47 rollback. Parked work remains closed; no GitHub. Clean builds use disabled Kotlin incremental/build caches, rerun tasks, one worker/in-process compilation and 4 GiB Gradle heap. Final source, APKs, local commit, total timing and runtime proof are recorded in the local issue ledger. The broad performance goal remains incomplete.
