# Display: ordered background writes for slider input

Display previously called `DisplayControl` directly from input callbacks. Each brightness update
made two `Settings.System.putInt` calls and one read-back. Each volume update read the stream's
maximum and set its level. Auto-brightness also wrote and read the system setting synchronously.
These calls ran on the UI/input thread. Initial/resume Display reads already ran on IO and were
not the issue changed here.

The input callback still publishes its local UI snapshot immediately. System writes now go to one
process-owned queue on Dispatchers.IO, with one executing write and at most one latest pending
value for each of brightness, auto-brightness and volume. Replacing a pending control removes and
reinserts its key to preserve the order of the last user choices. This is necessary because manual
brightness also disables auto-brightness. The last queued edit completes after leaving a pane;
Display resume reads await the queue before querying system state. The queue captures only the
application context, has no idle polling, and does not keep a Settings Activity alive.

The existing settings registry, preference schema, brightness/volume mapping and Night Shift
controls are unchanged. Slider accessibility labels now name the controls. Their fixed diagnostic
markers expose real bounds without exporting values or enabling Home input/search admission.

## Verified local checks

- A failing input-thread regression reproduced the old direct-write behavior before the queue
  implementation. The public-marker regression also failed before adding the fixed label map.
- Full debug and non-debuggable benchmark builds passed.
- Full JVM gate: 1,767 tests / 201 suites, zero failures, errors or skips.
- Seven queue tests cover off-caller execution, coalescing, final-value delivery, both orders of
  manual/automatic brightness, independent controls, failure recovery, and the resume barrier.
- With one provider write deliberately blocked, 100 brightness submissions produced exactly two
  writes: the executing first value and the final requested value. This is controlled backpressure
  evidence, not a measured Portal reduction percentage.
- One fixed-slider bounds/privacy regression verifies the public labels do not enable Home input,
  public long press or search, and do not export their label strings.

## Verified Family Area check

- Five native checks passed in 69.42 seconds, including installation and compilation verification.
- One slider gesture used two stable observations of its actual bounds. The actual system volume
  changed, stayed identical after header Back/Forward, and was restored to its original raw level
  and mute state. The gesture was not retried.
- Display and the history transitions stayed in the same Settings window. The candidate was
  retained, Home restored, the collector inactive, and configuration/photo-source snapshots
  unchanged. Living Area and the phone received zero operations.
- Eighteen offline driver tests passed, including the original nine safety guards plus strict new
  request admission, a single gesture obligation, and volume restoration failure handling.
- Brightness and auto-brightness queue/order behavior was JVM-tested; their physical settings
  were not changed during this native volume check.

Final installed APK SHA-256:
`3e562af3d9f20f59ca164e4f8d18b388bcdc5f2ef26b09277727a3cb21802064`.
Bootstrap SHA-256: `19109d2f5bb155a2cb1d071b1ed514b444f8279371ebece532c057f57138e29d`.
The APK is 25,386,112 bytes, non-debuggable, uses native R8 and one DEX, and retains the existing
package and signing certificate. Its profile resolves 8,622 methods / 2,561 classes, including
268 application methods / 71 application classes. Exact bundled/reference profile sets and
DEX checksums were verified against the installed artifact; own OAT reports speed-profile /
install-dm. This does not prove compilation of each individual method.

Receipts and archived unit XML results are in the primary workspace under
`build/fleet/diagnostics/settings-display-writes-2026-10-11/`.
Postflight SHA-256: `cef276ac5b69ba94f92efe4b3b8e958f74390dd09f702ef47a289c22fde654fb`.

The native check measures correctness and restoration, not frame-time improvement. The concrete
UI-thread change is three synchronous brightness service operations and two synchronous volume
service operations per input update becoming zero in their input callbacks. Actual IO write
counts on the device were not collected; the 100-to-two reduction above is the controlled test.

## Android reference

Android's coroutine guidance places blocking work behind a main-safe boundary. Its longer-lived
work guidance explains why a write that must survive navigation needs an owner outside the screen.
The queue's process lifetime is deliberate; it does not use GlobalScope or launch a job per sample.

- https://developer.android.com/kotlin/coroutines/coroutines-best-practices#main-safe
- https://developer.android.com/kotlin/coroutines/coroutines-best-practices#create-coroutines-data-layer

No fresh frame-time percentage, universal 60 FPS result, browser improvement or completed overall
performance audit is claimed by these checks.
