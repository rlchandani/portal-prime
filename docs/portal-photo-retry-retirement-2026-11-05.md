# Photo import retries after pairing renewal — 5 November 2026

**1.189 / code 183** is installed and fully hashed in both rooms. Switching the
viewed Portal now checks pairing ownership only for queued or sending copies.
Historical confirmed, failed, unconfirmed and cancelled results keep their
original captured session and cannot abort a valid new retry. Stale active work
and pending preflight still retire; retries still select only definite failures.

Thirteen frozen retirement cases reproduce eight assertion failures and five
controls; all thirteen pass afterward. The test is in the existing CI JavaScript
directory. Relocation changes only two relative imports; all assertions are
byte-identical and the canonical file is requalified against original/candidate.

The same frozen isolated Chrome fixture serves actual bundled HTML/JavaScript/
CSS and generated responses. Its full UI sequence confirms one photo, rejects
another, renews pairing, retries, then switches rooms with the retry in flight.
Baseline shows one of two confirmed and an unconfirmed retry; candidate shows
two of two confirmed. The confirmed photo is posted once and the failed photo
twice. All **17 browser child cases**, including 16 controls and 12 viewport/
theme/font checks, pass. Unknown/outside/WebSocket requests and page errors are
zero. First missing-Playwright setup failure is retained and excluded; installed
NODE_PATH repairs resolution without changing fixture assertions.

All **4,634 JVM / 482 suites**, **404 slideshow / 770 remote JavaScript checks**,
debug/optimized/R8/vital checks and independent review pass. Build: 96.20s.
Only the active-state filter/comment in remote/control-center.js changes in
production; core, CSS, all 22 other source assets and photo controller remain.

Exact optimized APK: 25,887,103 bytes, SHA256
`31d45dddf4443d5252afe4077759a6cd5bc54047a99ba8071d642d20eac4e68b`.
One app install per room, uncertain Living acknowledgment reconciled through
reads. All 43 Family / 30 Living preferences, four configuration maps and Family
runtime match; final captured fatal/ANR markers are zero. Copied registry files
retire and owner bytes remain unchanged.

Generated browser operations prove this UI/controller path, not real transfers,
phone/native browser behavior or an existing browser's cached asset refresh.
No household pairing/source/provider/account/authentication/push/publication;
public 1.83 remains. Held clocks stay. Original start: 01:22:57 UTC;
target: 01:37:57; hard limit: 01:52:57. Evidence:
`mocks/portal-photo-retry-retirement-20261105/`.
