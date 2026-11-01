# Queued SMB connection cleanup

**Fixed and deployed:** a connected SMB source waiting for a photo-host UI
callback is released if that callback is discarded. Source commit
**aac4e9e8** is on `codex/slideshow-smb-cleanup-20261101`.

## Cause and correction

Previously the source became controller-owned only inside the queued callback.
A hidden host retained that callback, but closing the host discarded it without
closing the connection. A rejected UI post had the same ownership gap.

`PausableFrameCallbacks.postOwned` now distinguishes callback admission from
discard. Admission transfers ownership to the callback. Rejection, removal or
terminal queue closure invokes its discard hook exactly once, outside the queue
lock. A stale detached wrapper cannot claim or release the resource again.
Existing ordinary posts retain their delay and dispatch behavior.

The SMB startup callback uses this owned API. Its discard hook closes the source
on the named `slideshow-close-smb` thread. Active-session stop and fallback
teardown use the same path, so killing the decode executor cannot discard its
queued teardown job. Network teardown does not run on the UI thread.

## Verification

| Check | Before | After |
| --- | --- | --- |
| Targeted queue/resource gate | **10 failures / 38 tests** | **38/38 pass** |
| Real TCP peer sees closure when a hidden queued callback is discarded | fails | passes |
| Real TCP peer sees closure after a late post to a closed host | fails | passes |
| Full unit gate | previous delivery: 3,657 tests | **3,668 tests / 387 suites**, zero failures, errors or skips |

The TCP tests use actual loopback sockets and asynchronous resource teardown;
the receiving peer must observe EOF. They do not authenticate to a NAS or
simulate successful SMB negotiation. Eleven tests were added. Queue tests also
cover rejection on resume, transport exceptions, reentrant cleanup, detached
wrappers, and worker/close races. Cleanup failures are reported after the other
discard hooks run. Independent source review found no issues.

Debug and optimized performance builds passed. Optimized non-debuggable APK
**b79b56c7…** is installed on Family Area with the existing signing certificate.
Its installed hash was rechecked. The deployment took **18.609 seconds** and
preserved **97 settings, 22 source fields**, persistent pin state and the exact
accessibility list. The original Dream remains active, the diagnostics
collector is inactive, and the system process stayed unchanged.
Living Area was not changed.

## Remaining acceptance

Family Area has no saved SMB host/share/credentials. Actual NAS behavior remains
unverified, and no NAS configuration was changed. This closes the connected
resource waiting-for-UI-delivery leak; it does not accept cancellation while
SMB connection establishment/listing is still in progress, or every SMB failure
path. It also does not measure panel FPS.

The paused-view observer capture, natural room-light comfort, cold startup,
animated-image residency and Home/Apps timing outliers remain open.
The full platform/screensaver/night-clock/music goal is incomplete.

See the [sanitized verification receipt](../mocks/portal-premium-player-20261030/evidence/family-smb-callback-cleanup/verification.json).
Private configuration snapshots, credentials and rollback APKs remain outside Git.
