# Startup stall attribution

The broader performance goal remains active. The previous slideshow comparison has mixed
medians and four worse candidate maxima. Independent per-component maxima cannot identify
the components of the same stalled frame, so do not assign cause from that evidence.

1. Verify the existing live fifteen-minute soak, preserving its exclusive hardware protocol.
2. On an isolated local branch, add one bounded worst-frame detail per Window histogram,
   copied from the same callback as total/first-draw. Reuse one component buffer per listener.
   Keep aggregate semantics, missing values, callback drops and invalid details explicit.
3. Add opt-in construction, UI-build, controller-start and WebView-construction spans to
   the existing bounded slideshow probe. They overlap; do not sum them as CPU time.
4. Observe meaningful failing tests, implement, independently review, then run the full
   unit/debug/benchmark gate. Preserve exact evidence and locally commit.
5. After the current soak is terminal and cleanup confirmed, deploy the diagnostics to both,
   run short bounded fresh-preview attribution captures and verify cleanup/config equality.
6. Use paired components and actual stage timings to select the next performance change.
   Separately investigate JS first-photo/transition work and cold startup. No speculative
   universal prewarm fix, delivered-FPS, leak-free or first-photo claim.

Only fixed labels, booleans and numbers leave devices. No household photo bytes, screenshots,
visible UI text, DOM content or secrets are exported. Session ownership and stop checks remain.
The frame callback timestamp is receipt time, not vsync or physical presentation time.
