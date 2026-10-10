# Native image resolution counters

An explicit Frame Performance capture includes `imageResolution` aggregate counters for local-file
calls through the slideshow bridge's `scaledUrlForSize` (including full-size `scaledUrl`). These are
bridge-resolution observations. They do not attribute a request to an active style, photo advancement,
frame presentation, a graphics stall or an oversized original.

The existing `EngineScaledCache.resolveAndRequest` path emits one of four fixed outcomes at the branch
that actually returns the file or null:

| Result | Meaning |
| --- | --- |
| `missing-original` | The original did not pass the resolver's existing file check. |
| `requested-tier-ready` | A nonempty derivative at the rounded requested tier was returned. |
| `larger-tier-ready` | The existing fallback search returned a larger ready derivative. |
| `original-fallback` | The original passed the initial file check, but no eligible ready derivative was returned; the bridge keeps its existing empty-string response so JavaScript uses the original URL. |

The original can already be sufficiently small. No dimensions are measured, so `original-fallback`
must not be described as oversized or interpreted as a cause of slow frames. A tier identifies the
cache derivative's configured long-edge target, not decoded pixel dimensions or delivered image size.
A file can also change between resolution and WebView consumption; these counters stop at resolution.

`deliveryPairs` is a detached fixed 5-by-5 requested/delivered tier table for successful derivative returns. It distinguishes a 1200-tier request served by 2400 from an ordinary 2400-tier hit without retaining per-photo records. Fallbacks and rejected observations do not enter the table.

`requestedTiers` counts every accepted resolution. `deliveredTiers` counts derivative returns only;
missing originals and original fallbacks have no delivered derivative tier. Keys are fixed at `600`,
`1200`, `1800`, `2400` and `custom-maximum`. Requests reuse production `derivativeEdge` rounding and caps,
including nonpositive and extreme integers. A nonstandard maximum is bounded to 1..2400 and grouped
under `custom-maximum`; standard maxima keep their numeric tier. Invalid maxima, delivered tiers and
inconsistent outcome/tier combinations increment only `invalidRecords`.

Only fixed outcome/tier identities and primitive totals are retained. The probe receives no URL,
filename, path, source identity, photo content or dimensions. JSON is constructed only on explicit
snapshot reads; snapshots are detached. No per-request journal, frame sampler, timing spans or extra
collector is added. Selecting a measurement before resolution binds it to that capture; replacement,
closure and the existing deadline reject a late observation. Counters update directly under the
existing session monitor on the bridge thread, without posting to Main. Inactive and closed selection
reads no clock and creates no token; inline bridge/probe wrappers avoid a resolver-closure allocation.

Instrumentation preserves the existing return value, empty response, readiness search, tier policy,
decoder queue ownership and submission count. It adds no file statistics, header reads, Bitmap work
or decoding. Provider and observation failures are contained so diagnostics cannot replace a real
resolution result or resolution failure. Remote URLs remain outside this local derivative probe.

## Validation scope

The focused JVM tests use synthetic byte files and intercept only `EngineDerivativeWork.Owner`
submission. They exercise the real resolver's requested hit, larger hit, custom maximum, original
fallback, missing path, empty/smaller derivative rejection and observation failures. Sample tests cover
canonicalization, fixed key bounds, invalid observations, privacy and detached snapshots. Capture
tests cover inactive/closed/expired admission, bridge-thread counting without Main posting, and
replacement/closure/expiry during resolution.

Main verified the inert seams' compilable RED with 17 tests, 15 failures, zero errors and zero skipped
before implementation. This worker ran no Gradle, packaged build, device interaction or network
requests. Main owns the full native test suite, APK checks and any two-device deployment/measurement.
Passing these tests establishes diagnostic behavior, not slideshow performance or a causal link
between fallback counts and previously observed frame stalls.

Main added two paired-tier contract tests, first reproducing two failures in eight sample tests
against the unpaired implementation. The integrated fixed matrix then passed the full 1448-test
native suite (zero failures/errors/skips), and debug/benchmark APKs contained the new native
counter keys and matched packaged assets. The background-startup experiment remains disabled.
An independent static review found no remaining verified blocker in the capture/resolve path.
The private exporter passed eleven fixed-shape, numeric-bound, consistency and privacy cases
and retains the existing fourteen-case owned-capture/Home-window protocol unchanged.
These gates still do not establish hardware performance improvement or pixel dimensions.
