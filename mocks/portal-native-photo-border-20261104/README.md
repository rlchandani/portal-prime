# Native photo edge verification

The unchanged generated fixture loads the actual debug SlideshowStage and draws
seven static software-Canvas captures. Baseline1.147 lacks an edge in five normal
whole-photo scenes; candidate1.148 passes all seven. Tiny and manual-crop controls
remain unchanged. Both runs retain28 colored corners, geometry, exact bitmap
identity/admission, opaque output and the previous sparse matte marks.

`native/build.sh <debug.apk>` freezes the Java fixture DEX and embeds the APK.
`native/repack.sh <candidate-debug.apk>` reuses that DEX. Root exclusively owns
candidate repack and the two once-only Family/API29 executions. Both runs retire.
Separate before/after directories retain all14 generated PNGs for pixel replay.
Original10:29:09–10:59:09UTC clock, target10:44:09; prior clocks remain.

Software rendering proves these generated scenes. Compositor, live motion,
dissolve, native clock/host, optimized runtime, API28, Living visuals and hardware
performance remain separate. See the linked delivery receipt for preserved room
state and exact installed artifact proof.
