# Browser companion: reuse visible photo thumbnails

Photos previously cleared its 24-entry thumbnail pool on every render. An explicit
Refresh starts status and photo-metadata reads; both completions can rebuild the
page, aborting pending requests and downloading unchanged thumbnails again.

The pool now binds pending work and ready blob URLs to the latest tile consumer.
Delivery runs after DOM attachment. Only one consumer is retained per entry, and
removed entries abort their requests and revoke their URLs. The existing bounds
remain 24 entries and four concurrent loads; aborted loaders occupy their slots
until they settle. Thumbnail identity includes the Portal endpoint, file path,
size and modification time. Older listings without complete version metadata
invalidate on each accepted metadata refresh. Room, tab and page cleanup remains.

## Verified browser result

The shipped controller and stylesheet ran in Chromium at 412 x 915 with 24
generated photos. No private Portal images were used.

| Check | Previous behavior | Current behavior |
|---|---:|---:|
| Initial thumbnail requests | 24 | 24 |
| Extra requests after one unchanged Refresh | 28 | 0 |
| Extra requests after five unchanged Refreshes | Not measured | 0 |
| Total requests when refreshing twice during initial loading | Not measured | 24 |
| Reloads after one photo modification changes | Not measured | 1 |
| Live blob URLs while the complete page is visible | Not measured | 24 |
| Live blob URLs after leaving Photos | Not measured | 0 |

Rebuilt tiles retained the same URLs, all 24 image sources were populated, and no
horizontal overflow appeared. Controller/pool regressions also exercise deletion,
pagination, room changes, late completions, retries and independent modal cleanup.
These are request and resource-ownership results; physical phone performance and
slideshow frame-rate improvements were not measured by this change.

## Build and Family Area deployment

- All 90 JavaScript tests passed, with zero failures, skipped or cancelled tests.
- Debug, optimized native-R8 benchmark and exact-candidate bootstrap builds passed.
- The Android unit gate passed 1,885 tests in 221 suites, with zero failures,
  errors or skipped tests.
- Thirty offline deployment-runner guard tests passed after parent review.
- The existing signing certificate was verified for the accepted APK and both new
  artifacts. Family Area retained candidate APK SHA-256
  `e0e2bda5e991d11182be33775a37c5964f20b1842378a337ef1835fc40f42445`.
- Family Area served both exact tested JavaScript assets. Native verification also
  proved ready Home before/after, unchanged configuration and source, and an inactive
  collector. No settings writes, Living Area operations or phone operations occurred.
- Exact installed SDK/bundled compilation profiles were verified: 8,667 method and
  2,576 class entries. Individual method compilation is not inferred from these sets.
  The inherited six-symbol coverage check remains obsolete; its decoder and DEX mapping
  passed, and its failed symbol assertion is not represented as a passing gate.

Native served-byte proof and browser interaction evidence are separate. Generated
Chromium photos establish the controller behavior; native proof establishes that
Family Area serves those tested bytes. No private response bodies, photos, URLs or
credentials were exported. Evidence is stored in the primary workspace's ignored
`build/fleet/diagnostics/remote-photo-reuse-2026-10-11` directory.
