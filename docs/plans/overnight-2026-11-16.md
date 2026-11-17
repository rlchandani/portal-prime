# Overnight queue — 2026-11-16 → 17 (owner asleep)

Constraints: quiet hours (22:00–08:00) — no audio on any Portal (keep STREAM_MUSIC at 0 during
tests and restore), minimal screen wakes, never force-stop the launcher, never screenshot Home
(family photos), only `main` on GitHub, max 2 concurrent Gradle builds (OOM seen with 3; use
-Xmx6g / kotlin 5g).

1. Settings Phase 2 (previews + lighter Sounds/Wallpaper/Display) — builder running → merge →
   silent device test on Family → gfxinfo per-page switch (target ≤1 janky, first frame <40 ms).
2. Music list scrolling (merged locally as 9fc7d40a) → measure Liked "See all" fling on Family
   (target <5% janky, p99 <24 ms). Do NOT `am force-stop` the launcher (builder's procedure said
   so — unsafe here).
3. Bug: MEDIA_PLAY_PAUSE / MEDIA_PAUSE key doesn't pause the new Music player — fix + test.
4. Perf pass #2: Home swipe start, first open of Apps/Tools tabs (p99 77/133 ms), memory after
   Music (213 → 307 MB), WebView release after leaving Music.
5. Security/code review of the week's additions: /remote/household share, /remote/ytmusic/*
   (history/suggest/search/playtrack/sleep), OTA (Portal + phone), follow-me broadcasts,
   YtQueryCompletions; fix CRITICAL/HIGH.
6. Overnight OTA proof: publish a release ~01:00 and do NOT trigger install; at ~05:30 confirm both
   Portals installed it by themselves in the 02:00–05:00 window.
7. Phone (Pixel, if idle/unlocked): re-check the Music search × clear button.
8. Device checks: guest-mode hiding; routine takeover footer vs the bug pill.
9. Voice assistant decision sheet (options, costs, privacy) for the morning.

Morning report: what shipped (versions), before/after perf tables, review findings + fixes, what
needs the owner (follow-me walk test in daytime, YouTube link for the OAuth demo video).
