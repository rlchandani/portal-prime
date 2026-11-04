# Buffered native media acceptance

Ten frozen cases invoke the actual debug APK selection, captured snapshot and
play/pause helpers on Family/API29 with fixture-owned MediaSession/MediaController
objects and generated metadata. Baseline1.149 has four product failures and six
controls; candidate1.150 passes all ten with the same frozen DEX and gates.

The prior token is obtained from an actual PLAYING selection. Only that session
may remain selected through BUFFERING. Captured state, exact title/artist/mediaID/
position/duration/token/seek and bounded owned transport callbacks are checked.
Each run releases13 owned sessions and its HandlerThread, restores isolated active
state, retains history, accesses no preferences, and retires package/process/files.

`native/build.sh <baseline-debug.apk>` freezes the fixture; root alone repacks
candidate with `native/repack.sh <candidate-debug.apk>` and owns both device runs.
Notification listener and live session manager are never started. No actual audio,
provider, household session, mounted UI or optimized/API28 claim is made.
Cross-package selection priority is covered by JVM/source checks.
Original10:40:15–11:10:15UTC clock retained; selection time is separately recorded.
