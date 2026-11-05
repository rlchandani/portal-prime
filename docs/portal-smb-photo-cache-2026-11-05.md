# SMB photo cache delivery

Both rooms now have the exact optimized **1.208/code202** APK. SMB image fetching
uses the existing MediaCache: a warm hit decodes the saved encoded photo without
opening its image transport; a miss reads bounded original bytes, publishes them
within the existing budget, and decodes them even if publication fails or eviction
immediately removes the copy. Keys distinguish server, share, account, domain and
share-relative path without including passwords. Cache initialization stays on
the source worker. The existing registry exposes its opt-in cache switch to SMB
on device and remote; persistence, defaults and storage limits are unchanged.

Each SMB image stream registers under the controller's transport lock after
checking the captured epoch. Cancellation can close an admitted held read; every
path unregisters/closes it. Epoch guards reject stale reads and decoded bitmaps.
The custom SMB fetch remains non-null, retaining its existing transport and native
Filmstrip format fallback. Cancellation-versus-cache-publication is not atomic;
the contract rejects late presentation, with the existing bounded cache retained.

Nine generated actual-helper JVM methods verify repeat image hits, source identity,
cache-off behavior, corrupt-hit recovery, failed writes/eviction, read/decode
cancellation, bounded/read-failure cleanup and an owned held stream. One registry
method verifies SMB visibility on device/remote without changing saved values.
All **4,754 JVM tests / 496 suites**, debug/optimized/R8/vital and both JavaScript
gates pass. Exact APK inspection confirms the signer and packaged source assets.
These are helper/registry tests, not a historical behavioral RED or live NAS,
native SMB playback, EXIF decoder or actual controller-stop race acceptance.

Optimized APK: **25,904,007 bytes**, SHA256
`cd12ddb0d874f24dc7c8903abe79a639a3d840308e5c4de3bf3556093f92a886`.
One install per room: Family's complete APK readback matches; Living's uncertain
acknowledgment settled through nine read-only observations, with exact bytes/hash
and ready service, without a second install. Both normal frame requests pass.
All **43 Family / 30 Living** preference files and four saved maps/revisions are
byte-exact. Family font/accessibility, original Chromium activity, resumed frame
and awake display remain. Captured logcat (1,610/1,749 lines) and diagnostics have
zero fatal/ANR markers. Owner registry bytes remain exact; copied records removed.

No household photo source, provider/account, phone, public manifest or publication
change occurred. SMB startup still connects/lists; captions may separately read
metadata. Offline album discovery and reconnect are follow-up work. The earlier
static photo controller acceptance remains bound to its exact **1.207** APK.

Human-authorized sequential clock: **15:35:26 UTC** start, **15:40:26** discovery
cap, **15:50:26** target, **16:05:26** hard stop on 5 November. Source candidate
selected before the cap; device delivery and restoration finished by **15:43:58**.
Prior issue clocks remain unchanged. Receipt:
`mocks/portal-smb-photo-cache-20261105/verification.json`.
