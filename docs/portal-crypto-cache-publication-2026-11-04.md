# Crypto response cache publication

Optimized Immortal **1.132/code126** is installed in Family Area and Living Area.
Both complete installed APK readbacks match **25,884,988 bytes**, SHA-256
`41e7d2fe5069432a31e624b7578b2a09fc4e467728f8a8851247800bd688e838`.
The package, iteration signer and nine screensaver assets remain unchanged.

A stale usable crypto response was previously replaced by every non-null HTTP
body before decoding. An HTTP200 HTML error page, truncated JSON, object-shaped
error or empty body therefore erased prior prices and acquired a fresh timestamp.
The widget then displayed its existing empty state and later offline fetches
could no longer recover the previous prices.

Crypto now validates a fresh body with its existing decoder before returning it
to the raw cache. Failed decoding returns null to the existing stale-fallback
path, preserving both the prior body and timestamp. The nullable decoder
distinguishes failure from a valid empty result; valid `[]` and markets responses
omitting configured coins still replace old quotes. The existing parser facade,
coin order, configured labels, missing-field defaults and sparkline behavior
remain. Successful fresh responses are decoded again when returned from the
cache; this narrow correction adds no shared cache policy or injection seam.

## Verification

The generated desktop fixture executes actual public `CryptoFeed.fetch`,
`Http`, the decoder and `HttpCache`, using a fake connection and test
SharedPreferences. Its ten cases cover the four malformed bodies, invalid first
response, valid replacement, valid empty/omitted-coin responses, fresh hit and
ordinary offline fallback. Assertions include literal quotes, exact prior raw
value/timestamp, publication counts, later offline results and disconnection.

The initial URL-constructor mock disrupted the Gradle worker and is excluded
from product evidence. The repaired fixture runs generated transport in its own
JVM, with a bounded wait and owned output cleanup. Only the wrapper has a JUnit
annotation; child cases cannot be directly discovered outside that process.
An initial repaired wrapper reproduces five assertion failures/five controls
before the production edit. The final frozen fixture, including timeout and
discovery safety repairs, reproduces the same failures against the preserved
original compiled runtime. All ten cases pass against the candidate.

All **4,391 JVM methods / 458 suites** pass with zero failures, errors or skips.
The ten child executions are reported separately from the single Gradle wrapper.
The six existing parser cases pass. Debug, optimized/R8 and vital lint pass;
both APKs retain nine exact packaged screensaver assets and the iteration signer.
Independent source, fixture, XML, hash and native-report review finds no blocker.

The same frozen framework-only Java fixture on Family Android10/API29 loads the
actual baseline and candidate debug APKs, calling public fetch on a background
thread with real Android JSON and preferences. An owned process-local HTTPS
handler admits only generated responses for the expected request. Five failed
baseline modes and five controls become ten passing candidate cases. It has
**no INTERNET permission**, does not contact CoinGecko, redirects preferences to
its own package and rejects unexpected preference names.

Baseline debug: **51,372,381 bytes**,
SHA-256 `ffab4191ef612b9cb5ba3033a9c33d34eff0805534ae7de1d13255c369923fdf`.
Candidate debug: **46,726,887 bytes**,
SHA-256 `0a9b8df0bbbd8bb2d6ddc1d0284964129802407d590db29c81907d17bab51f88`.
Native reports check label/price, cache body/timestamp and request/disconnect
counts. They do not compare every quote field or prove disk durability after
restart. Both owned fixture runs are uninstalled.

## Delivery and limits

Family's single install returned Success. Living lost its response; seven
read-only observations reconcile one install with the full APK hash, ACK
uncertain and no retry. All **43 Family and 30 Living preference files**, their
file sets, four complete maps, room names and Dev Mode match the baseline.
Family font/accessibility, original Chromium activity, resumed photo frame and
awake state remain. Bounded logcat responses contain 1,635/1,974 lines with no
fatal/ANR markers; these observations do not prove every runtime path.

Native acceptance covers generated debug execution on Family API29 and
same-process preference readback. Live providers, mounted Home UI, Living
functional execution, API28, optimized runtime and durable restart remain open.
Normal HTTP disconnection is observed, without owned Tools cancellation or
forced child-timeout testing. Syntactically admitted arrays retain existing
quote-field defaults; already-poisoned fresh entries and future timestamps are
unchanged. News and Converter are untouched.

Original registries, public release manifest/catalog and native fleet CLI remain
exact. Copied registries and temporary documentation environments are removed
at closure; private rollback and before/after snapshots are retained. No phone,
household media, provider/account action, push or publication.

This distinct cache-publication unit retains **05:46:54–06:16:54 UTC November4**,
including discovery, fixture repair, implementation, checks, delivery and closure.
The earlier Converter clock hypothesis retains the original News discovery
clock; current future-timestamp and alarm observations remain unselected.
Legacy-night, Weather and all other parked scopes retain their original clocks.
The queue adds this delivered item while preserving all 120 existing task
objects and 405,436 bytes of their raw suffix. Weather remains default.
The whole-product goal remains active.

Evidence: `mocks/portal-crypto-cache-20261104/evidence/verification.json` and `native/`.
