# Native photo hardware acceptance

Three static generated scenes from the actual **1.155/code149 debug APK** pass
both an attached hardware Window capture and software Canvas rendering on
Family/API29: portrait whole fit, panorama whole fit and Gallery Spread.
The existing subtle doodle mattes and thin warm photo edges appear in both paths.
Whole-photo geometry, bitmap identity, admitted memory and opaque output remain.
This adds hardware acceptance to the earlier matte and border software checks;
it makes no production source, app version or release change.

Each scene waits for two committed Window frames before PixelCopy. Each path
independently passes the unchanged corner, edge, matte and opacity predicates:
16 literal corners and 16 photo edges per path. Hardware and software geometry
and memory admission agree; antialiasing pixels need not be byte-identical.
All six generated PNGs are independently replayed against the literal samples
and matte ranges. Independent source and artifact reviews bind the inputs,
compiled fixture and results.

Normal retirement stops the actual Stage, observes zero budget and still-live
input bitmaps, removes the Stage, waits for a committed host frame, then recycles
inputs. All three scenes retire normally; the owned fixture package, process and
external directory are absent after execution.

The initial strict Context adapter failed during retirement on a typed
InputMethodManager name request. Its partial portrait result is excluded.
The sole adapter amendment returns null for that observed type name and counts
17 admissions, without obtaining a service. All assertions and frame fences
remain unchanged. The literal zero real-service field expresses source
containment, not global instrumentation. Fault cleanup is not accepted as
equivalent to normal retirement. The author admission retains its earlier RUN
hash; the final preparation and reviews bind the revised instructions.

Both rooms retain exact optimized **1.155/code149**, independently fully hashed
without another app install. Family 43/43 and Living 30/30 preference files and
four complete configuration maps remain exact. Family font, accessibility,
original Chromium activity, resumed photo frame and awake display are retained.
Saved Shuffle/fit remains; renderer support does not establish the currently
selected room style.

This is bounded current-only acceptance of an 800×480 owned Window scene.
Production Dream/controller/source/clock integration, motion, dissolve, resize,
optimized runtime, API28, Living visuals and hardware performance remain separate.
No household photo, provider, account, phone or publication operation occurred.
The previous 1.155 Android build and 4,507 JVM checks belong to its delivery;
this acceptance slice does not rerun or claim new Android build gates.

Original discovery **12:15:12 UTC**, selection **12:17:30**, target **12:30:12**,
hard deadline **12:45:12**, without reset. Earlier parked scopes and clocks remain.
Fixture source, six generated captures and bounded receipts:
`mocks/portal-native-photo-hardware-20261104/`.
