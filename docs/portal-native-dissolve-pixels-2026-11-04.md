# Native dissolve pixel acceptance

The actual **1.162/code156 debug SlideshowStage** passes hardware and software
pixel checks at controlled 25%, 50% and 75% dissolve fractions on Family/API29.
This is acceptance of existing behavior, with no production source, app version
or room app installation change.

Two generated portrait photos remain whole and retain their bitmap identities,
geometry and 3,072,000-byte admission. The actual retained, paused ObjectAnimator
sets the incoming alpha; the outgoing composition stays opaque. Each capture
waits for two committed Window frames before PixelCopy, paired with software
Canvas rendering of the same Stage.

All 30 fixed blend samples pass the independently derived rounded source-over
RGB oracle. Maximum observed channel error is 1, within the predeclared tolerance
of 3. All 2,304,000 captured pixels are opaque. Each image retains 242,400 matte
pixels outside the photo envelope: hardware has 238,111 base pixels and 4,289
quiet doodle pixels; software has 238,471 and 3,929. No matte pixel fails.
The independent reviewer and a separate root PNG decoder reproduce these counts.

One normal Stage retirement stops rendering, verifies zero budget and still-live
inputs, detaches the Stage, commits a host frame, then recycles the inputs.
The fixture package, process and external directory are absent afterward.
The generated Context records zero unexpected preference, resolver or service
requests; seven typed input-method requests return null. These counters establish
source containment, not systemwide service instrumentation.

Both rooms retain the exact optimized **1.162/code156** APK, fully read back
without installation. Its SHA-256 is
`2c43d9e7a2fa0f76a82a4796317fbe2279747d62467ee0b64b2982c131d283c3`.
All 43 Family and 30 Living preference files and four complete configuration
maps remain identical. Family font, accessibility, original Chromium activity,
resumed photo frame and awake display are retained. Saved Shuffle/fit remains;
supported rendering does not establish the currently selected room style.

This checks three controlled fractions in an owned 800×480 Window. Natural
progression, smoothness/FPS, pause/resume, cover, resize, Dream/controller/clock,
optimized runtime, API28 and Living visual acceptance remain separate.
Earlier motion and resize state checks retain their original scope.
No household media, provider, account, phone or publication operation occurred.
The previous Android build and 4,525 JVM tests belong to the Lamp delivery;
this slice built only its isolated fixture and ran one native execution.
An unavailable host Pillow import preceded pixel evaluation; the root used
standard-library PNG decoding without changing assertions or rerunning devices.

Original discovery **14:07:59 UTC**, selection **14:11:59**, target **14:22:59**,
hard deadline **14:37:59**, without reset. Prior parked scopes and clocks remain.
Frozen sources, six generated captures, combined review and bounded receipts:
`mocks/portal-native-dissolve-pixels-20261104/`.
