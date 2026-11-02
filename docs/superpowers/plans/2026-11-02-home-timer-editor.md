# Home timer editor ownership

Bounded unit: **07:23:23–07:53:23 UTC**, including configuration restoration.
Previous goal turn made verified progress: native contract `2682e3a9`, companion
compatibility `610899f…`, and interactive roadmap `d0709c6e` are committed.
Base for this new isolated branch is `d0709c6e`.

Home's countdown and screensaver use `TimerStore`; Tools kitchen timers use
`TimerConfig`. Preserve both stores, their saved data and their alarm ownership.
The Home widget's editor must read and operate on its countdown, using existing
page/dialog components. No timer migration or broader widget redesign.

- [x] Preserve current device settings/layout/timer state; reproduce the wrong owner.
- [x] Route Home to countdown controls; retain kitchen timers in Tools.
- [x] Run full JVM/debug/optimized gates and independent source review.
- [x] Verify editor/widget start, pause, resume, cancel, custom duration and alarm stop.
- [x] Restore the temporary widget, screensaver and timer state.
- [x] Record artifact/source identity and exact device delivery separately; stop at cap.

Family Area is the primary QA device. Living receives only the final accepted
artifact. No GitHub authentication/publication, new service or credentials.

Completed: see `docs/portal-home-timer-delivery-2026-11-02.md` for exact acceptance, delivery and restoration limits.
