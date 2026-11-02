# App-store metadata response budget — Portal 1.86

Delivered exact optimized **1.86 / code 80**, `com.immortal.launcher.debug`, to Family Area and Living Area. Both complete installed APK hashes match `6cc4ec5b4f9e75021d3d8bd39830b11cb1d2c1cbdff9cdb70bc230964289b485` (25,861,122 bytes); accepted signing identity retained. Source: `148b6a2677f694bebbee20f9d3ec04ab08dc7a88`.

Original issue clock: **21:43:20–22:13:20 UTC**, 2 November 2026, including discovery, verification and cleanup. No exhausted issue reopened.

## Change and reproduced problem

The original default `StoreCatalog.latestVersionCode` transport accepted otherwise-valid generated JSON padded beyond 4 MiB. Three original real TCP checks failed; the ordinary chunked UTF-8 control passed. The first baseline attempt hit a parallel-file compilation race and did not reach behavioral tests; its failure is retained separately.

`StoreMetadataHttp` now limits existing catalog, F-Droid and direct version metadata to **4 MiB of actual response bytes**. Announced oversize is rejected before acquiring the body. Unknown-length responses read at most budget plus one byte. Incomplete declared bodies fail. Acquired streams close on success/failure, and `StoreCatalog.httpGet` disconnects in `finally`. Version pins, request headers/timeouts/redirects, worker separation and catalog fallback retain their existing behavior. APK downloads continue streaming separately to disk.

Root catalog: 24,043 bytes/26 entries; bundled fallback: 18,989 bytes/22 entries. This limit has substantial current-catalog headroom and applies only to metadata. Arrays, UTF-8 conversion and JSON parsing still allocate within that bound; no whole-heap or frame-rate improvement is inferred.

## Validation and delivery

| Gate | Evidence |
|---|---|
| Full JVM | **3,952 passed /414 suites**, zero failures/errors/skips |
| New regression methods | 24 stream and8 real TCP/fixture checks |
| Build | Debug, optimized performance/R8 and vital lint passed; terminal exit0 |
| Independent review | Integration clear; server-thread assertion propagation weakness corrected and reviewed |
| Fixture negative control | Deliberately wrong header surfaced on JUnit thread; final XML confirms execution |
| Family native normal catalog | Actual Apps→App Store rendered **27 apps** on original and final APK; bundled asset has22 |
| Family consumer APIs | Final `/remote/catalog` and `/apps` each returned27 distinct package IDs with literal success |
| Delivery | Existing verified deployer fully hashed the installed APK in both rooms |
| Preservation | Complete saved profiles/layouts/sources/screensaver config unchanged in both rooms; Family font/accessibility unchanged |
| Recovery/cleanup | Normal Family photo frame visibly restored; original Chrome task27174 remains listed; owned registry removed and agent closed |

The first final build's2 GiB Kotlin compiler heap reached99.99% occupancy and572.822 seconds of full GC. Its verified owned process tree was stopped (terminal143). The same gates then passed in **124 seconds** with the per-invocation `-Pkotlin.daemon.jvmargs=-Xmx6144m`. No persistent Gradle/JVM configuration changed. This explains the observed build bottleneck; it is not a Portal runtime benchmark. Carry the successful invocation flag into the next local full-source multi-variant build.

The Store activity is private: direct shell launch was denied, and normal Apps navigation was used. No permission/export modification. Immediate captures included preceding/blank transition frames; only settled captures support native catalog acceptance. The older black-handoff/performance investigations stay parked. No catalog app was installed.

## Platform scope and limits

The Portal native store/backend is affected. Browser and Android companion consume its existing unchanged catalog contract; neither client needed a rebuild. Living received final artifact and configuration verification only. Companion1.7.7 remains a physically uninstalled emulator candidate. Weather daily geometry and other exhausted issues keep their original boundaries.

No on-device oversized-body or gzip failure injection, real F-Droid version resolution, owner OOM, total-heap reduction, FPS, Chrome process retention or total request deadline is claimed. Whole-product acceptance remains open. No account setup, credential extraction, GitHub authentication/publication or Home Assistant change.

[Android HttpURLConnection documentation](https://developer.android.com/reference/java/net/HttpURLConnection) explains bulk streaming, `disconnect()` and automatic gzip decompression. Android clears compressed length/encoding headers for automatic decoding, so counting actual read bytes remains essential. Existing connection/read timeouts remain.

Public receipt: `mocks/portal-widget-gallery-20261102/evidence/store-metadata-budget-verification.json`. Private command logs, profiles, captures and rollback artifact: `/private/tmp/portal-store-metadata-budget-20261102`; no owner photos or credentials were published.
