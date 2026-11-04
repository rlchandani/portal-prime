# Gallery keeps complete tiles clear of the clock

Gallery Wall previously centered justified rows across the full viewport, ignoring
measured clock/calendar reservations. It now sizes and offsets those same rows
inside the existing `SS.photoLayoutArea`. Local padding stays positive in a narrow
free strip. Opaque photo backings, composed doodles, contained photos and fades stay.

Resize notifications coalesce into one owned pausable frame and update existing
cells using their retained photos. No owner reconstruction, photo resolution/load,
selection or random draw is added. Cleanup removes the listener. Native bridge,
helper, scheduler and CSS remain unchanged.

Thirteen frozen cases reproduce **ten failures and three controls**, then pass
unchanged. Eleven existing lifecycle cases pass. Coverage includes scaled native
coordinates, clock/panel reservations, narrow space, absent/malformed fallback,
coalescing, pause/resume, initial decode, deferred replacement and terminal cleanup.
Independent live-fade resize preserves ownership and retirement. Paired traces
retain five random draws and exact URLs/backgrounds/classes/opacity/resources/
loads/cursor/timers; only cell geometry differs. Gallery's intended geometry golden
changes to `239aa48e016a8c83876cd308e2183b9002e89c4ff286519e57b6ec8fddc95ac9`;
all eleven other goldens remain exact.

All **4,414 JVM tests / 461 suites**, **385 slideshow checks**, **743 application
JavaScript checks**, debug/optimized builds, R8 and vital lint pass.

Same frozen generated Family/API29 native fixture uses measured hidden DOM bridge
regions: bottom35%, right45%, and bottom25% plus right22%. These are large-clock
stress rectangles, not either room's settings. Baseline intersections **1/1/3**
become **0/0/0**, with **32 photo corners**, opaque backings and quiet doodles retained.
Unknown requests are zero; compositor/destruction/package/process checks pass.
Independent review verifies unchanged fixtureDEX/gates, replays scaled rectangle
pairs from both reports and all candidate corner/doodle pixels from actual PNGs.
The collector used identical screenshot filenames, so candidate collection
replaced baseline PNGs. Baseline pixel controls are supported by its frozen native
report; independent baseline geometry replay is report-based. No baseline PNG
replay or extra device rerun is claimed.

Desktop actual production preview keeps nine complete tiles clear of its simulated
clock at2560×1099 and after resize to1200×770. Actual native FaceRenderer/clock,
Gallery Print/Spread, Android resize/swaps/scheduler/motion/performance/API28/Living
visuals remain separate.

Both rooms contain exact optimized **1.144/code138**, 25,886,643
bytes, SHA-256 `a3a3373d0c69029a6649e899cb7f24f78a0e9066111a14ad8284f0b448a246a7`. One install per room; Living's
uncertain acknowledgment is reconciled with 6
read-only observations. All **43 Family/30 Living preference files** and four
complete maps match. Family font/services/originalChromium/resumedframe/awake
remain. Saved Shuffle/fit stays; installed support does not establish the current
style. Public release remains **1.83/code77**.

Original **09:21:05–09:51:05 UTC November 4** clock retained, selected09:27:13;
closure timing/cleanup recorded separately. All earlier parked scopes/clocks,
135 queue objects/raw suffix and Weather default remain. No phone, source-media
transfer, provider/account operation, push or publication. Whole-product goal active.

Portable fixture, generated images and evidence:
`mocks/portal-gallery-clock-space-20261104/`.
