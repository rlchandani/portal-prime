# Synthetic native artwork probe

`command=musicArtwork` runs before UiAutomation and generic private diagnostics.
It returns only numeric/boolean Bundle fields. `musicArtworkCompleted=true` requires
all checks and cleanup to succeed; `failureStage` is numeric (1 fixture, 2 local
decode, 3 HTTP decode/cache reflection, 4 exact-body controls, 5 rejection, 6 cache).

Assets were generated off-device with Python's stdlib (`struct`, `zlib`, `binascii`):
RGB PNGs, each row prefixed with filter byte 0, compressed IDAT, standard IHDR/IEND
and CRC32 chunks. `high-4096.png` is 4096x4096; eight solid-color `cover-N.png`
files are 1024x1024 with distinct pixels. No device-sized source bitmap is created.
The test APK copies only the high-resolution fixture into a probe-owned UUID
directory in the target cache (instrumentation runs with the target UID) for the
real MediaArt local-URI path. All served bytes are synthetic.

The probe calls shipped MediaArt and MultiRoomArt, using only 127.0.0.1 HTTP with
6-second socket-close deadlines, a 90-second server lifetime, at most 32 requests,
bounded request headers, and bounded thread joins. It proves native sampled decode
(local <=384 px, HTTP <=384/1024 px), exact 8 MiB payload acceptance, oversized
local/EOF/declared/chunked body and header rejection, and actual cache accounting
and eviction after eight distinct full-size decoded covers. Cache reflection reads
accounting and constructed synthetic keys only; it never snapshots contents/URLs.
Finally removes those keys, synthetic files, sockets and threads; returned/shared
bitmaps are never recycled.

The 8 MiB cap counts **body payload**. EOF rejection needs one additional byte to
distinguish a full body from overflow (`eofOverflowProbeBytes=1`). HTTP headers and
chunk framing are separate overhead: the exact chunked control has an 8 MiB
payload plus `exactChunkFramingBytes` and `exactChunkHttpHeaderBytes`. Oversized
Content-Length/chunk declarations allow the loader to reject before reading the
payload. The server nevertheless offers the full valid PNG plus padding, so a
truncated response cannot produce a false rejection result. The header probe
sends a syntactically valid header exceeding 32 KiB
with a valid image body; control routes establish image decoder validity first.
Any expired socket/server deadline makes the overall result fail, including on
rejection routes, so a timeout cannot masquerade as successful bounds enforcement.

This is runtime **decode/cache** evidence only. It does not prove cancellation,
audio playback, MediaSession artwork delivery, or runtime audio resource cleanup.
Run only after the parent has cleaned up its actual audio fixture. Compilation
and device execution are deliberately deferred to the parent.

From the worktree, parent build and run commands:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb shell am instrument -w -e command musicArtwork \
  com.immortal.launcher.debug.test/com.immortal.launcher.PortalUxInstrumentation
```

Use the parent's normal installation flow for the debug app and its test APK.
