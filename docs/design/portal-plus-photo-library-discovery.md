# Portal photo-library discovery — 2026-10-09

The companion Photos screen previously listed its upload destination rather than the
collection already on each Portal. Both rooms had nine photos in Pictures/PortalInstaller
and three in the shared-storage root, while Pictures/Immortal was empty or absent.

## API behavior

GET /remote/photos?path=/sdcard&recursive=1 returns flattened file metadata, direct-folder
counts, recursive:true and an explicit truncated flag. Existing shallow folder requests
retain their previous schema and 500-photo cap. The companion advertises photoLibrary
and only presents All photos after a successful recursive response.

Discovery performs no image decoding, retains no metadata cache, uses streaming directory
enumeration, and does not hold the photo upload/thumbnail mutation lock. The limits are
1,000 photos, 512 directories including the root, 20,000 entries, depth 8 and a cooperative
500 ms deadline. Filesystem operations cannot be forcibly preempted by this deadline.
Hidden paths, child symlinks and Android app-storage paths remain excluded by policy.

Browsing is read-only. Photo-source settings, persisted configuration and slideshow modes
are unchanged. No new settings fields or credential stores are introduced on the Portal.

## Evidence

The focused policy/index checks and all 881 JVM tests passed, as did the debug build.
The APK was deployed to both Family Area and Living Area through the existing fleet
channel, with installed APK hashes verified. Each device returned 12 photos in two
folders without truncation. Full slideshow snapshots matched before and after deployment.

Twenty paired desktop requests per room measured median/p95 latency of 62.08/108.88 ms
for Family Area and 61.25/81.99 ms for Living Area. The physical Pixel's own client also
passed 20 requests per room, with median/p95 150/231 ms and 106/118 ms respectively.
These are metadata HTTP measurements, not rendered UI or slideshow frame-rate claims.

Toolkit 1.5.3 was installed on the paired Pixel. Its 463 JVM tests and 17 generated-fixture
emulator UI workflows passed. The visible physical-phone workflow remains pending a
secure-screen unlock; it has not been claimed as tested. The 303.57-second soak completed 100 requests per room with zero failures and consistently
returned 12 photos. Family median/p95 was 48.44/67.31 ms; Living was 55.81/74.11 ms.
The raw summary is retained privately in the primary checkout's build/fleet/diagnostics directory.
The broader Portal performance and UX goal remains active.
