# Browser connection-status ownership delivery

Bounded unit: **2026-11-02 08:46:02–09:16:02 UTC**, including cleanup.
Branch `codex/pwa-connection-status-20261102`, accepted parent `22db0e62`.
Local, unpublished **1.69 / code 63**, exact artifact delivered to both rooms.

## Problem and correction

On actual paired Family 1.68, delaying the initial `/remote/companion` read and
selecting Settings reproduced `net::ERR_ABORTED`, followed by Offline/Retry over
a usable settings page. The ordinary read handler ignored cancellation correctly,
but `selectRoom` had a second continuation without page/request ownership. It
interpreted missing companion metadata as a transport failure after cancellation.

That continuation is removed. The existing current-read status handler owns
connection success/failure. Navigation and visibility resume immediately replace
missing/offline metadata reads. Established metadata remains reused; the normal
poll cadence, authentication, pairing, settings and API contracts are unchanged.

## Regression evidence

Independent worker added 22 tests against the shipped controller/client. Before
the fix, 12 passed and 10 failed: four false Offline races and six missing
immediate replacement reads. Removing only the continuation left six failures.
The complete proposed fix passed all 22 in an in-memory probe. Main reproduced
10 failures from the actual file, then applied the focused production correction.
Main's 36 focused ownership/poll/storage tests passed.

Cases include page/room changes, cancellation, Refresh, hide/resume, late status
results, current network/401 failures, unsupported legacy metadata, storage
unavailability, settings schema errors and photo-source failures. These fixture
checks establish controller behavior, not physical network outages or phone UI.

A patched-controller preview against the actual Family API replaced the initial
read before a poll: two status requests, trace Connecting → Connected →
Connected/Portal+, Settings visible, no Offline state. This preview intercepts
the controller response; it is separate from installed-artifact acceptance.

## Gates, artifact and rollout

The actual local readiness command exited 0 in **399,875 ms** (6m 40s rounded),
including 40 slideshow JS, 676 bundled JS, 161 Python and 3,800 JVM tests across
399 suites, with zero failures/errors/skips. Optimized assembly, R8, vital lint,
version sync and actual SDK package/signature checks passed. The checker archived
399 prior JVM reports before requiring fresh results. All fifteen recorded
processes exited 0. Its receipt retains published/deployed=false: the later room
installs have their own receipts.

- Package: `com.immortal.launcher.debug`, performance / non-debuggable.
- Version: **1.69 / code 63**; APK **25,860,686 bytes**.
- APK SHA-256: `e7f6e0ceffa2685d2db40a57cdf86b2f6746dea3087a9bdca463264e0c71ccb3`.
- Signer SHA-256: `4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`, matching accepted1.68.
- Selected source fingerprint: `57004a74e283aa9695c82bfeb05390cc65f9226fb3b91a254f8b17f6c6fc59c4` over
  1,347 files. Execution HEAD was22db0e62, dirty=true.
- Family: one in-place ADB install returned Success; full installed APK bytes,
  hash and version matched, followed by the actual browser checks below.
- Living: one dispatch, restart acknowledgement uncertain, ready after three
  read-only observations. A separate exact APK/version/room readback matched.
  No second install was dispatched.

The APK grew **20 bytes** versus1.68. This is an artifact-size observation,
not a new FPS or native latency benchmark. Both complete settings profiles,
screensaver configuration and layout XML compare unchanged against saved room
baselines. No wipe, permission grant, new pairing, publication, hosted CI or
physical phone installation occurred. The accepted1.68 baseline is retained.

Private observations, configuration baselines, install claims and check receipts
live under `/private/tmp/portal-pwa-connection-status-20261102`. Full credentials,
profiles and uncropped screenshots are not copied into the repository.

## Actual installed Family browser acceptance

The installed server supplied the corrected controller without asset interception.
At390×844, six delayed-initial-read trials (Settings twice, Apps, Remote, Photos,
Home) all cancelled the first GET, issued its immediate replacement and displayed
Connected/Portal+, without any Offline trace or horizontal overflow. Recorded
click-to-visible-metadata samples were175,127,147,146,130,119ms (119–175ms).
These browser samples include automation/DOM waiting; they are not optical panel
latency or an arbitrary-network percentile guarantee.

Six further connected tab switches added **zero** companion/status GETs and
kept Connected. Controlled transport failure showed Offline/Retry; Retry restored
Connected. Controlled401 showed Pair again and issued zero automatic requests
after two tab switches. Removing interception and reloading reused the real
existing pairing successfully. Physical outage/revocation was not induced.
The test’s first unscoped Home locator was ambiguous between navigation and a
remote-control button; no Home action was clicked. The navigation-scoped rerun
passed all six switches.

The instrumented browser page was closed and a fresh page in the existing paired
context remained connected on Settings, without test trace globals or overflow.
Only a cropped connection-status capture was recorded privately. The pre-existing
favicon401 remains outside this fix; no whole-PWA console-clean claim is made.

Family’s original Immortal PhotoDreamService was observed running again after
cleanup. A direct shell dream call returned a permission denial despite shell
exit0; the existing SystemUI Somnambulator entrypoint was then launched, and later
dream-service readback confirmed the original component. No device permission or
system configuration was changed. This is state cleanup, not a reopening of the
parked dream/handoff work. Living received exact artifact/config verification;
Family remains the primary browser/interaction acceptance target.

Next small polish finding: Family1.69 serves no explicit favicon link. An actual
browser probe returned401 for `/favicon.ico` and200 `image/png` for the already
public `/remote/app-icon?size=64`. The manifest/touch icons are already public;
this does not establish broken installation. Reusing that public icon is queued
for a separate bounded unit. Physical phone acceptance remains unavailable:
current ADB lists no phone and mDNS discovery returned no services.
