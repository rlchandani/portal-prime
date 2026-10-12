# Browser Apps search: reuse visible icon images

Apps previously cleared the thumbnail pool whenever its page rendered. Each search keystroke
therefore fetched the same still-visible icons again. An initial operations response could
also discard the images just loaded for the app list. The shared browser client requests
icons with no-store, and the on-device icon route regenerates PNG data for each request.

Apps now retains the currently visible installed package entries within the same loaded
inventory snapshot. The existing ImagePool asynchronously rebinds pending/ready images to the
newest attached DOM consumer. Hidden entries are released and pagination remains bounded to
24 images. A fresh inventory response invalidates artwork, including installed-app updates
whose list metadata contains no version. Existing page/Portal changes still clear the pool;
uninstalled catalog entries never request package icons. Core image-pool behavior, APIs,
Kotlin sources, settings, and the Android companion app remain unchanged.

## Verification

Seven new controller regressions cover five-keystroke reuse, narrowing/widening membership,
pending consumer rebinding, explicit refresh with identical metadata, pagination, identical
package names across Portals, and installed/uninstalled catalog entries. The corrected baseline
ran seven tests: five expected failures and two passing invalidation/budget checks. Full browser
gate: 108 passed, zero failures/cancellations/skips. Independent read-only source review found
no verified issue. Debug, optimized native-R8 and exact-candidate bootstrap builds passed.
Full Android unit gate: 1885 tests / 221 suites, zero failures/errors/skips. The deployment helper
passed 44 offline guards and the parent independently reran those guards successfully.

Real Chromium used generated app data at 412 x 915:

| Workload | Before | Candidate |
| --- | ---: | ---: |
| Extra icon requests for five keystrokes with four apps remaining visible | 20 | 0 |
| Existing icon object URLs retained | No | Yes |
| Search input focus retained | Yes | Yes |
| Horizontal overflow | No | No |

Narrowing to one app fetched zero icons and retained its original URL. Widening fetched only
three released images. Explicit Refresh fetched four fresh images and invalidated every old
URL. Actual PNG decoding succeeded in Chromium. The fixture process and both test tabs were
closed after evidence was saved. Browser fixture data is generated; this is not a physical
phone/private paired-session test. The page still renders new card DOM during search; this
change removes redundant image requests rather than claiming all DOM work is eliminated.

## Family Area deployment

One native trial passed and retained the candidate after two installs (bootstrap, candidate)
and two verified install returns to Home. Both served JavaScript assets matched the browser
proof byte-for-byte. Complete bundled/reference inventories matched: 8667 methods / 2576
classes, including 285 application hot methods / 75 application classes. Own OAT metadata
reports speed-profile / install-dm; individual compiled-method execution is not established.

Settings/source snapshots remained unchanged, Home was restored and collectors were inactive.
No setting edits, app launches, private paired-browser interaction, Living Area operations or
phone operations occurred. Native proof checks deployment/profile integrity; Chromium proves
the generated search workload. No FPS, tap latency, website-readiness or long-uptime gain is
inferred. Reload an existing browser page to load the updated controller assets.

APK SHA-256: `db5fa88deadc698e917fd6214665bbaa13c0a80b1ad6c06d2cd976754493d935`.
Source SHA-256: `a5f279798bc368e9c402e7c01b3193b72f29a84e020e96eac4bc5064164772e5`.
Controller SHA-256: `58ff5bd45d2b19daaff9ea2692ab347b11cd18012744d1b6965ff9bade00b615`.
The original issue window is 2026-10-12 07:51:20 through 08:21:20 UTC; no clock reset or native
retry. The ledger records full closure after local commit and cleanup. Evidence resides at
`~/portal-prime/build/fleet/diagnostics/remote-app-icon-search-2026-10-12`.
The broader performance/UX goal remains active and incomplete.
