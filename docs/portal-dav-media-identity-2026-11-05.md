# WebDAV media identity — 5 November 2026

**1.190 / code 184** is installed and fully hashed in both rooms. A listing that
repeats `a.jpg, a.jpg, b.jpg` with a two-item limit now returns `a.jpg, b.jpg`.
Duplicate image or enabled-video URLs no longer displace later distinct media.
Admission uses the exact already-resolved URL, preserves first occurrence/order,
and leaves excluded videos outside the limit. Folder traversal, href/XML
resolution, origin/authentication/transport, failures and cancellation remain.
Production changes are three lines.

Eight frozen real-HTTP tests call the public listing methods: four duplicate
regressions fail on baseline and four controls pass; candidate passes all eight.
The actual debug APK on Family Android10/API29 calls both public methods through
a verified classloader. The same frozen, nondelegating in-memory fixture also
improves from four of eight to eight of eight. Generated requests preserve
PROPFIND/Depth/User-Agent and stream-close/disconnect behavior. Both native
scratch paths retire. The fake setter directly accepts PROPFIND; real Android
method fallback, transport/authentication and decoding remain separate.

All **4,642 JVM tests / 483 suites**, debug/optimized builds, R8/vital checks,
package/signer checks and independent review pass. Build: 99.85s. All 23 packaged
source assets, photo controller and Home/SMB source remain exact. Optimized APK:
25,903,483 bytes, SHA256
`537ac7575c3e360f5d06760b6835d2e8c376a8b3f8051b52801f406fd0a62e8d`.
One install per room; uncertain Living acknowledgment is reconciled read-only.

All **43 Family / 30 Living** preference files and four complete maps match.
Family runtime is preserved; both frame requests pass, Family resumes awake.
Captured fatal/ANR markers are zero. Copied registries retire; owner bytes match.
No household server/source/media, phone/provider/account/auth/push/publication
operations. SMB Home fallback is a separate source-only finding, unimplemented
and not bundled. Mounted pixels, optimized/API28/Living helper behavior remain
separate; earlier parked clocks and public version1.83 remain.

Original **01:43:42 UTC** start, **01:58:42** target and **02:13:42** hard stop
retain one clock through discovery, verification, delivery and closure. Evidence:
`mocks/portal-dav-media-identity-20261105/`.
