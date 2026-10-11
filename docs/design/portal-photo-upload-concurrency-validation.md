# Portal photo-upload concurrency validation

The paired photo routes used to hold the read/delete/thumbnail lock while receiving
and validating an upload. A paused upload could therefore stall photo browsing and
unrelated uploads. Transfer, fsync and validation now operate on a hidden temporary
file outside that lock. Only exclusive destination reservation and atomic publication
hold the shared mutation lock. Existing image/size/MIME checks remain in place.

The folder is rechecked at publication, filename collisions never replace an existing
photo, and reads retain their opened file descriptor across delete/reupload. Concurrent
creation of the upload folder accepts another upload's successful creation.

## Verification

- Red: both newly added paused-transfer/paused-validation tests failed before the fix.
- Final full gate: 1817 tests in 210 suites,
  zero failures, errors or skips; debug and optimized benchmark APKs built successfully.
- Eight new JVM regressions cover concurrent staging, blocked publication, collision
  ownership, folder replacement, existing destination preservation and failed transfer cleanup.
- The first full check caught temporary-file cleanup failing through macOS's canonical
  path alias. That failure and the passing rerun are retained, rather than discarded.
- 40 offline native-runner checks passed. Parallel static review identified and resolved
  concurrent mkdir handling and diagnostic worker-lease cleanup.
- Family Area only: 2 native checks of the actual photo routes with a fixed
  generated 128 x 128 JPEG in app-private cache. No user's photo bytes or paths were exported.

| Native check | JPEG bytes | Concurrent read | Read completed before upload release | Workers terminated |
|---|---:|---:|---|---|
| 1 | 936 | 1 ms | True | True |
| 2 | 936 | 2 ms | True | True |

Each check confirmed no partial destination was published, both read/upload bytes
matched the generated JPEG, and the fixture was removed. The diagnostic retains its
busy lease after a worker timeout; a later call may reap it only after the old group
terminates, preventing repeated requests from accumulating worker groups.

The admitted candidate was retained with Home restored, collector inactive and exact
configuration/source equality. Zero setting edits, phone operations or Living Area
operations occurred. APK/profile mapping is verified against the same immutable source
inventory and existing signing identity. Individual method compilation is not inferred
from whole-profile validation.

## Scope and limits

This is a concurrency fix and functional device check, not a UI latency benchmark.
The read timings above are two small generated fixtures, not a claim about a large
library, 60 FPS, network speed, peak memory or CPU percentage. External replacement of
an upload folder intentionally prevents publication and may leave a hidden staging file
in the renamed folder; cleanup avoids deleting an unrelated file through a changed path.

Evidence: `~/portal-prime/build/fleet/diagnostics/photo-upload-concurrency-2026-10-11`.
Candidate APK SHA-256: `04b9670b0beba09eacba14fad7236c995e8108d4dfddc203ea823b97d7ae92af`.
Source inventory SHA-256: `255e0b288c25adb272a369fd0601c0002492a1e78004dd54e55998550a725d69`.
Postflight SHA-256: `847dc67c255d32710c0f570404f8d287226d411a71523885062b3ce5af89dbcf`.
Recorded at 2026-10-11T23:58:23.644147+00:00.
