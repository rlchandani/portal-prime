# SMB slideshow startup failures

**Source fix verified:** an exception while preparing the SMB photo listing
now releases the source instead of bypassing the queued-delivery cleanup.
Recoverable startup or handoff exceptions route to the existing built-in
photo fallback when the host is still active.

The bounded issue started at **08:35:32 UTC**, with a **09:05:32 UTC**
hard limit. The branch is `codex/slideshow-smb-listing-lifetime-20261101`;
source commit is **f188e068**.

## Cause and correction

The previous worker called `connect()` and `listImages()` before posting its
owned UI callback. Cleanup covered empty results and discarded callbacks,
but an exception before handoff escaped without releasing the source.
For example, reading an entry's metadata sits outside `SmbSource`'s
best-effort `share.list()` catch.

`prepareSlideshowSource` now owns the resource until preparation and handoff
complete. Null preparation, preparation exceptions and handoff exceptions
release it. The queue receives the same atomic discard callback, so repeated
discard and discard-followed-by-error do not run the owner's release twice.
The original error remains primary if release also throws.

The controller uses its existing `slideshow-close-smb` teardown thread.
An exceptional listing no longer blocks the photo worker on protocol close
before it can recover. This is an error-path resource/work ownership fix;
it adds no per-frame work, settings or new network protocol behavior.

## Verification

| Gate | Evidence |
| --- | --- |
| New startup regression gate against the behavior-equivalent scaffold | **10 tests, 9 failures**, zero test errors |
| Corrected startup + queue + socket + SMB-listing gate | **53/53 pass**, zero failures, errors or skips |
| Full unit task | **3,678 tests / 388 suites**, zero failures, errors or skips |
| Actual SMB listing function | An injected entry-metadata exception preserves the error, skips handoff and closes the mocked share |
| Actual loopback TCP connection | Preparation failure closes the socket; its peer sees EOF before test cleanup runs |
| Review | No blockers in the helper or controller integration |

Ten startup tests were added. Their cases include deferred ownership,
synchronous rejection, repeated discard, preparation/handoff errors, cleanup
error suppression and late discard after failure. Existing queued-connection
and asynchronous loopback retirement checks are included in the 53-test gate.

The red run is a JVM regression against the extracted old startup behavior,
not a measured leak on the installed Portal or a NAS performance benchmark.
The TCP case exercises a real socket, not SMB authentication or negotiation.

## Family Area delivery

Debug and optimized performance builds passed with the existing signing
certificate. Optimized non-debuggable APK **eb6296d3…** is installed on
Family Area; its installed hash was independently rechecked.

The deployment took **18.022 seconds** and preserved all **97 settings**,
**22 photo-source records**, persistent pin state and the exact accessibility
configuration. The original Dream is active, Android's system process stayed
unchanged, and a fresh health check found zero active performance listeners
and no active instrumentation. Living Area was not changed.

See the [sanitized verification receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-smb-startup-failure/verification.json).
Credentials, original profiles and rollback APKs remain outside Git.

## Acceptance limits

This does not cancel an SMB operation that is still blocked in connection
establishment, authentication or listing. Protocol teardown can also wait for
its own timeout; dispatching it off-thread does not prove immediate NAS release.
Those paths remain separate acceptance work.

Family Area has no saved SMB source, so actual NAS failure recovery and its
displayed fallback remain unverified. No NAS settings or credentials are changed.
Panel FPS and whole-platform performance are not established by this gate.
Provider/audio wiring remains deferred; the full goal is incomplete.
