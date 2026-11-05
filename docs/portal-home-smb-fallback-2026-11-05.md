# Home SMB photo fallback — 5 November 2026

**1.191 / code 185** is installed and fully hashed in both rooms. Home's Photo
memory card now tries later SMB images when an earlier file is unreadable,
corrupt, returns no bitmap or throws an ordinary open/decode exception. It stops
at the first successful bitmap. The existing60-file listing request, per-attempt
checkpoint and share/stream closure remain; other sources, decoder, EXIF,
cache, UI and source-worker implementation are unchanged.

Ten frozen cases call the actual private Home loader with generated SMB and
Android decoder substitutes. Baseline has five intended AssertionErrors and
five controls; candidate passes10/10. The cases cover bad bounds, null bitmap,
decoder/open exception, absent stream, first-success stopping, empty/all-bad
lists, failed connection and the existing cap. Created streams equal closed
streams; each source closes exactly once under this direct invocation.
No assertion or input changes between runs.

All **4,652 JVM tests / 484 suites**, debug/optimized builds, R8/vital checks,
package/signer verification and independent source review pass. Build:
98.47s. All23 packaged source assets,
screensaver controller, WebDAV/SMB source and public version remain exact.
Optimized APK: 25,887,099 bytes, SHA256
`f986656038449d55c2f1a630869aabe9ba82509a8feebe0fc9d1c392aacc0731`. One install per room; Living's uncertain
acknowledgment is reconciled by readback.

All **43 Family / 30 Living** preference files and four complete maps match.
Family runtime is retained; both frame requests pass, Family resumes awake.
Captured fatal/ANR markers are zero. Copied registries retire; owner bytes match.
Generated JVM selection/closure evidence does not establish live NAS access,
real SMB enumeration, native decoding/pixels, Compose/cache or worker ownership/
cancellation/optimized runtime. No household media/source/phone/provider/account/
auth/push/publication; earlier parked clocks and public1.83 remain.

The original **01:43:42 UTC** discovery clock, expired **01:58:42** target and
**02:13:42** hard stop stay; implementation resumed **01:58:18** without reset.
Evidence: `mocks/portal-home-smb-fallback-20261105/verification.json`.
