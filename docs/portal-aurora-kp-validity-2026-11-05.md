# Aurora feed reading validity

Delivered optimized **1.197/code191** to both rooms, with complete installed APK byte/hash readback and one install attempt each.

The shared Kp feed reader admitted NaN, infinity and finite values outside its documented 0–9 scale. Those values could flow into cached readings or forecast peak selection. One final admission guard now returns no reading unless the extracted value is finite and within 0 through 9. Valid endpoints, fractions, numeric strings and both legacy/current row shapes retain their meaning.

The current-feed null path still uses the existing cached fallback; the forecast loop still skips null readings. These caller effects are confirmed by source inspection, not an executed HTTP/cache/outlook workflow. Field precedence, coercion, timestamp handling, forecast horizon, cache duration, location and scientific classification are unchanged.

Thirteen frozen JVM methods through the actual private getter improved from **seven assertion failures/six controls to thirteen passes**. They cover NaN/infinity, negative/above-nine values, valid endpoints/fractions/strings, lowercase fallback and missing/malformed rows. Method counts include multiple vectors. Host JSON already rejected the two object-infinity methods at baseline.

Eight frozen literal vectors through the **actual debug APK getter on Family API29** improved from **five mismatches/three valid controls to eight passes**. Android framework JSON admitted object Infinity at baseline, unlike host JSON. Host and native inputs/counts differ. Class origins and identical Java/JAR/driver are verified; no URL opens occurred and both owned scratch paths were removed. No live NOAA, HTTP, cache, location, forecast aggregation, classification or rendered outlook acceptance is claimed.

All **4,708 JVM tests/490 suites**, debug/optimized packaging, R8 and vital checks passed in **116.08 seconds**. Independent source review passed; root rehashed all20 bindings. All23 source assets and the signing certificate match1.196. Public manifest1.83 remains.

All43 Family/30 Living preference files, file sets and four complete settings/source/layout maps were preserved. Family font, accessibility, original Chromium activity, resumed frame and awake state were preserved; both normal-frame requests passed. Captured diagnostics are scoped samples. Copied registries were removed and owner registry hashes match.

[Generated delivery summary](portal-delivery-summary-1.197.md) and `mocks/portal-aurora-kp-validity-20261105/verification.json` record evidence and limits. Optimized getter execution, API28, Living native and full outlook acceptance remain separate.

Original start **2026-11-05 03:45:03 UTC**, discovery cap **03:50:03**, target **04:00:03**, hard limit **04:15:03**. No clock reset; previous held scopes and the whole product goal remain open.
